package cz.creeperface.nukkit.gac

import cn.nukkit.Player
import cz.creeperface.nukkit.gac.checks.FakePlayer
import cz.creeperface.nukkit.gac.checks.data.*

/**
 * Created by CreeperFace on 31.7.2017.
 */
class ACData(val player: Player) {

    val motionData = MotionData()

    var fakePlayer: FakePlayer

    val packetsData = PacketsData()

    val speedData = SpeedData()

    val collisionData: BlockCollisionData

    val antiCheatData = AntiCheatData()

    val killAuraData = KillAuraData()

    init {
        val pos = player.clone()

        antiCheatData.lastPos = pos
        antiCheatData.lastGroundPos = pos
        antiCheatData.lastJumpPos = pos
        this.speedData.lastNonSpeedPos = pos
        this.speedData.lastNonBhopPos = pos
        this.collisionData = BlockCollisionData(pos)

        when {
            player.isSneaking -> speedData.lastSpeedType = SpeedData.SpeedType.SNEAK
            player.isSprinting -> speedData.lastSpeedType = SpeedData.SpeedType.SPRINT
            else -> speedData.lastSpeedType = SpeedData.SpeedType.WALK
        }

        fakePlayer = FakePlayer()
    }
}
