/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2023 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.world

import net.ccbluex.liquidbounce.config.Choice
import net.ccbluex.liquidbounce.config.ChoiceConfigurable
import net.ccbluex.liquidbounce.config.NamedChoice
import net.ccbluex.liquidbounce.config.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.repeatable
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoClicker
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleBlink
import net.ccbluex.liquidbounce.render.*
import net.ccbluex.liquidbounce.render.engine.Color4b
import net.ccbluex.liquidbounce.render.engine.Vec3
import net.ccbluex.liquidbounce.render.utils.rainbow
import net.ccbluex.liquidbounce.utils.aiming.Rotation
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsConfigurable
import net.ccbluex.liquidbounce.utils.aiming.raytraceBlock
import net.ccbluex.liquidbounce.utils.block.getCenterDistanceSquared
import net.ccbluex.liquidbounce.utils.block.getState
import net.ccbluex.liquidbounce.utils.block.searchBlocksInCuboid
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.combat.CpsScheduler
import net.ccbluex.liquidbounce.utils.entity.eyes
import net.ccbluex.liquidbounce.utils.entity.getNearestPoint
import net.ccbluex.liquidbounce.utils.item.findBlocksEndingWith
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.client.option.KeyBinding
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket
import net.minecraft.util.Hand
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import java.awt.Color

/**
 * Nuker module
 *
 * Destroys blocks around you.
 */
object ModuleNuker : Module("Nuker", Category.WORLD) {

    val mode = choices("Mode", OneByOne, arrayOf(OneByOne, Nuke))

    private object Swing : ToggleableConfigurable(this, "Swing", true) {
        val visualSwing by boolean("Visual", true)
    }

    private val ignoreOpenInventory by boolean("IgnoreOpenInventory", true)
    private val switchDelay by int("SwitchDelay", 0, 0..20)

    private val comparisonMode by enumChoice("Preferred", ComparisonMode.CROSSHAIR, ComparisonMode.values())

    init {
        tree(Swing)
    }

    /**
     * Makes a safe platform
     */
    private object Platform : ToggleableConfigurable(this, "Platform", true) {

        val size by int("Size", 3, 0..5)

        val renderHandler = handler<WorldRenderEvent> { event ->
            val matrixStack = event.matrixStack
            val base = Color4b(Color.GREEN)

            renderEnvironmentForWorld(matrixStack) {
                val playerPosition = player.blockPos.down()

                for (x in -size..size) {
                    for (z in -size..size) {
                        val vec3 = Vec3(playerPosition.x.toDouble() + x, playerPosition.y.toDouble(),
                            playerPosition.z.toDouble() + z)
                        val box = Box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)

                        val baseColor = base.alpha(50)
                        val outlineColor = base.alpha(100)

                        withPosition(vec3) {
                            withColor(baseColor) {
                                drawSolidBox(box)
                            }

                            withColor(outlineColor) {
                                drawOutlinedBox(box)
                            }
                        }
                    }
                }
            }
        }

    }

    init {
        tree(Platform)
    }

    /**
     * Blacklist of blocks that are usual not meant to be broken
     */
    private val blacklistedBlocks = findBlocksEndingWith("BEDROCK", "DRAGON_EGG").toHashSet()

    object OneByOne : Choice("OneByOne") {

        private var currentTarget: DestroyerTarget? = null

        override val parent: ChoiceConfigurable
            get() = mode

        private val range by float("Range", 5F, 1F..6F)
        private val wallRange by float("WallRange", 0f, 0F..6F).listen {
            if (it > range) {
                range
            } else {
                it
            }
        }

        private val forceImmediateBreak by boolean("ForceImmediateBreak", false)
        private val rotations = tree(RotationsConfigurable())

        val color by color("Color", Color4b(255, 179, 72, 255))
        val colorRainbow by boolean("Rainbow", false)

        val repeat = repeatable {
            if (!ignoreOpenInventory && mc.currentScreen is HandledScreen<*>) {
                wait { switchDelay }
                return@repeatable
            }

            currentTarget = null

            if (ModuleBlink.enabled) {
                return@repeatable
            }

            updateSingleTarget()

            val curr = currentTarget ?: return@repeatable
            val currentRotation = RotationManager.serverRotation

            val rayTraceResult = raytraceBlock(
                range.toDouble() + 1, currentRotation, curr.pos, curr.pos.getState() ?: return@repeatable
            ) ?: return@repeatable

            if (rayTraceResult.type != HitResult.Type.BLOCK || rayTraceResult.blockPos != curr.pos) {
                return@repeatable
            }

            val blockPos = rayTraceResult.blockPos

            if (blockPos.getState()?.isAir == true) {
                return@repeatable
            }

            val direction = rayTraceResult.side

            if (forceImmediateBreak) {
                network.sendPacket(
                    PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, blockPos, direction)
                )
                swingHand()
                network.sendPacket(
                    PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, blockPos, direction)
                )
            } else {
                if (interaction.updateBlockBreakingProgress(blockPos, direction)) {
                    swingHand()
                }
            }
        }

        val renderHandler = handler<WorldRenderEvent> { event ->
            val matrixStack = event.matrixStack
            val base = if (colorRainbow) {
                rainbow()
            } else {
                color
            }

            renderEnvironmentForWorld(matrixStack) {
                val pos = currentTarget?.pos ?: return@renderEnvironmentForWorld
                val vec3 = Vec3(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
                val box = Box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)

                val baseColor = base.alpha(50)
                val outlineColor = base.alpha(100)

                withPosition(vec3) {
                    withColor(baseColor) {
                        drawSolidBox(box)
                    }

                    withColor(outlineColor) {
                        drawOutlinedBox(box)
                    }
                }
            }
        }

        /**
         * Chooses the best block to break next and aims at it.
         */
        private fun updateSingleTarget() {
            val targets = searchTargets(range)

            if (targets.isEmpty()) {
                return
            }

            for ((pos, state) in targets) {
                val raytrace = raytraceBlock(
                    ModuleNuker.player.eyes, pos, state, range = range.toDouble(),
                    wallsRange = wallRange.toDouble())

                // Check if there is a free angle to the block.
                if (raytrace != null) {
                    val (rotation, _) = raytrace
                    RotationManager.aimAt(rotation, openInventory = ignoreOpenInventory, configurable = rotations)

                    this.currentTarget = DestroyerTarget(pos, rotation)
                    return
                }
            }
        }

    }

    object Nuke : Choice("Nuke") {

        override val parent: ChoiceConfigurable
            get() = mode

        private val nukeRange by float("Range", 5f, 1f..100f)
        private val cps by intRange("CPS", 40..50, 1..200)
        private val doNotStop by boolean("DoNotStop", false)

        // Do not put into CPS scheuduler into tree - we do not want user to change it
        private val cpsScheduler = CpsScheduler()

        // Chat feedback for the user
        // TODO: Move this to separate module?
        private var sendOutPackets = 0
        private val packetChronometer = Chronometer()

        val repeat = repeatable {
            if (!ignoreOpenInventory && mc.currentScreen is HandledScreen<*>) {
                wait { switchDelay }
                return@repeatable
            }

            if (ModuleBlink.enabled) {
                return@repeatable
            }

            val targets = searchTargets(nukeRange)
            if (targets.isEmpty()) {
                return@repeatable
            }

            val cps = cpsScheduler.clicks({ ModuleNuker.enabled }, cps)

            for ((pos, _) in targets.take(cps)) {
                network.sendPacket(
                    PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, Direction.DOWN)
                )
                sendOutPackets++

                if (Swing.enabled) {
                    sendOutPackets++
                }
                swingHand()

                if (!doNotStop) {
                    network.sendPacket(
                        PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.DOWN)
                    )
                    sendOutPackets++
               }
            }

            if (packetChronometer.hasElapsed(1000)) {
                chat("[Nuker] Sent $sendOutPackets packets in the last second.")
                sendOutPackets = 0
                packetChronometer.reset()
            }
        }

    }

    private fun swingHand() {
        if (Swing.enabled) {
            if (Swing.visualSwing) {
                player.swingHand(Hand.MAIN_HAND)
            } else {
                network.sendPacket(HandSwingC2SPacket(Hand.MAIN_HAND))
            }
        }
    }

    /**
     * Searches for targets around the player
     */
    private fun searchTargets(radius: Float): List<Pair<BlockPos, BlockState>> {
        val radiusSquared = radius * radius
        val eyesPos = player.eyes

        return searchBlocksInCuboid(radius.toInt()) { pos, state ->
            !state.isAir && !blacklistedBlocks.contains(state.block) && !isOnPlatform(pos)
                && getNearestPoint(eyesPos, Box(pos, pos.add(1, 1, 1)))
                .squaredDistanceTo(eyesPos) <= radiusSquared
        }.sortedBy { (pos, state) ->
            when (comparisonMode) {
                ComparisonMode.CROSSHAIR -> RotationManager.rotationDifference(
                    RotationManager.makeRotation(pos.toCenterPos(), player.eyes),
                    RotationManager.serverRotation
                )
                ComparisonMode.DISTANCE -> pos.getCenterDistanceSquared()
                ComparisonMode.HARDNESS -> state.getHardness(world, pos).toDouble()
            }
        }
    }

    private fun isOnPlatform(block: BlockPos) = Platform.enabled
        && block.x <= player.blockPos.x + Platform.size && block.x >= player.blockPos.x - Platform.size
        && block.z <= player.blockPos.z + Platform.size && block.z >= player.blockPos.z - Platform.size
        && block.y == player.blockPos.down().y // Y level is the same as the player's feet

    data class DestroyerTarget(val pos: BlockPos, val rotation: Rotation)

    enum class ComparisonMode(override val choiceName: String) : NamedChoice {
        CROSSHAIR("Crosshair"), DISTANCE("Distance"), HARDNESS("Hardness")
    }

}
