package cz.creeperface.nukkit.gac.player

import cn.nukkit.Player
import cn.nukkit.block.Block
import cn.nukkit.block.BlockID
import cn.nukkit.entity.item.EntityBoat
import cn.nukkit.event.entity.EntityMotionEvent
import cn.nukkit.event.player.PlayerMoveEvent
import cn.nukkit.event.server.DataPacketReceiveEvent
import cn.nukkit.level.Location
import cn.nukkit.math.AxisAlignedBB
import cn.nukkit.math.Vector2
import cn.nukkit.math.Vector3
import cn.nukkit.network.protocol.*
import co.aikar.timings.Timings
import cz.creeperface.nukkit.gac.ACData
import cz.creeperface.nukkit.gac.GTAnticheat
import cz.creeperface.nukkit.gac.checks.PacketCountCheck
import cz.creeperface.nukkit.gac.checks.collidesWith
import cz.creeperface.nukkit.gac.utils.debug
import kotlin.collections.set
import kotlin.math.ln

class CheatPlayer(val p: Player, cheatPlayer: ICheatPlayer) : ICheatPlayer by (cheatPlayer) {

    override val acData = ACData(p)

    private val blocksUnder = mutableListOf<Block>()

    override var currentPos = p.clone()
        private set

    private val packetsData = mapOf(
            InteractPacket.NETWORK_ID to PacketCountCheck().Entry(),
            MovePlayerPacket.NETWORK_ID to PacketCountCheck().Entry()
    )

    init {
        this.p.setCheckMovement(false)
    }

    fun handlePacket(packet: DataPacket): Boolean {
        with(this.p) {
            if (packet.pid() == MovePlayerPacket.NETWORK_ID) {
                handleMovePacket(packet as MovePlayerPacket)
                return true
            }

            if (packet.pid() == ProtocolInfo.PLAYER_ACTION_PACKET) {
                if ((packet as PlayerActionPacket).action == PlayerActionPacket.ACTION_JUMP) {
                    val time = System.currentTimeMillis()

                    acData.antiCheatData.lastPacketJump = time

                    if (acData.antiCheatData.lastGroundPos.distance(this) < 0.4) {
                        jump()
                    }

                    val motionData = acData.motionData
                    if (!motionData.isEmpty && !motionData.ground) {
                        val timeY = motionData.timeY

                        if (time > timeY && time > motionData.time && motionData.groundTime != -1L && time - motionData.groundTime > 1500) {
                            motionData.ground = true
                        }
                    }
                }
            }

            return false
        }
    }

    fun handleMovePacket(packet: MovePlayerPacket) {
        with(this.p) {
            if (!isConnected) {
                return
            }

            Timings.getReceiveDataPacketTiming(packet).use {
                val ev = DataPacketReceiveEvent(this, packet)
                this.server.pluginManager.callEvent(ev)
                if (ev.isCancelled) {
                    return
                }

                if (teleportPosition != null) {
                    return
                }

                val newPos = Location(packet.x.toDouble(), (packet.y - 1.62f).toDouble(), packet.z.toDouble(), packet.headYaw.toDouble(), packet.pitch.toDouble())
                val dis = newPos.distanceSquared(this)
                var revert = false

                if (dis == 0.0 && (packet.yaw % 360).toDouble() == this.yaw && (packet.pitch % 360).toDouble() == this.pitch) {
                    return
                }

                if (dis > GTAnticheat.conf.maxDistance) {
                    this.sendPosition(forceMovement
                            ?: this, packet.yaw.toDouble(), packet.pitch.toDouble(), MovePlayerPacket.MODE_RESET)
                    return
                }

                if (!this.isAlive || !this.spawned) {
                    revert = true
                    forceMovement = Vector3(this.getX(), this.getY(), this.getZ())
                }

                if (forceMovement != null && (newPos.distanceSquared(forceMovement) > 0.2 || revert)) {
                    this.sendPosition(forceMovement, packet.yaw.toDouble(), packet.pitch.toDouble(), MovePlayerPacket.MODE_RESET)
                } else {
                    packet.yaw %= 360f
                    packet.pitch %= 360f

                    if (packet.yaw < 0) {
                        packet.yaw += 360f
                    }

                    this.setRotation(packet.yaw.toDouble(), packet.pitch.toDouble())
                    forceMovement = null
                    val packetEntry = packetsData[MovePlayerPacket.NETWORK_ID]?.let {
                        processPacketCheck(it)
                    }

                    val distanceSquared = newPos.distanceSquared(this)
                    if (this.chunk == null || !this.chunk.isGenerated) {
                        val newPosV2 = this.getLevel().getChunk(newPos.x.toInt() shr 4, newPos.z.toInt() shr 4, false)
                        if (newPosV2 != null && newPosV2.isGenerated) {
                            if (this.chunk != null) {
                                this.chunk.removeEntity(this)
                            }

                            this.chunk = newPosV2
                        } else {
                            revert = true
                            nextChunkOrderRun = 0
                        }
                    }

                    val newPosV2 = Vector2(newPos.x, newPos.z)
                    val distance = newPosV2.distance(this.getX(), this.getZ())

                    if (!revert && distanceSquared != 0.0) {
                        val dx = newPos.x - this.getX()
                        val dy = newPos.y - this.getY()
                        val dz = newPos.z - this.getZ()

                        newPosition = Vector3()
                        fastMove(dx, dy, dz)
                        if (newPosition == null) {
                            return  //maybe solve that in better way
                        }
                        newPosition = null

                        val diffX = this.getX() - newPos.x
                        val diffY = this.getY() - newPos.y
                        val diffZ = this.getZ() - newPos.z

                        if (diffX != 0.0 || diffY != 0.0 || diffZ != 0.0) {
                            this.x = newPos.x
                            this.y = newPos.y
                            this.z = newPos.z
                            val radius = this.width / 2
                            this.getBoundingBox().setBounds(this.getX() - radius, this.getY(), this.getZ() - radius, this.getX() + radius, this.getY() + this.height, this.getZ() + radius)
                        }
                    }

                    val from = Location(this.lastX, this.lastY, this.lastZ, this.lastYaw, this.lastPitch, this.getLevel())
                    val to = this.location

                    if (revert) {
                        if (distanceSquared == 0.0) {
                            this.speed = Vector3(0.0, 0.0, 0.0)
                        }
                    } else {
                        val firstMove = this.firstMove

                        this.firstMove = false
                        this.lastX = to.x
                        this.lastY = to.y
                        this.lastZ = to.z
                        this.lastYaw = to.yaw
                        this.lastPitch = to.pitch

                        if (!firstMove) {
                            val blocksAround = if (this.blocksAround != null) ArrayList(this.blocksAround) else null
                            val collidingBlocks = if (this.collisionBlocks != null) ArrayList(this.collisionBlocks) else null

                            val moveEvent = PlayerMoveEvent(this, from, to)

                            this.blocksAround = null
                            this.collisionBlocks = null
                            blocksUnder.clear()
                            currentPos = to.clone()

                            this.server.pluginManager.callEvent(moveEvent)

                            revert = moveEvent.isCancelled

                            if (!revert) {
                                if (to != moveEvent.to) {
                                    this.teleport(moveEvent.to, null)
                                } else {
                                    this.addMovement(this.getX(), this.getY(), this.getZ(), this.getYaw(), this.getPitch(), this.getYaw())
                                }

//                                debug { "newPos: ${this.position}" }
                                updateFallState(this.isOnGround)
                                if (this.isOnGround || this.getY() > this.highestPosition) {
                                    this.highestPosition = this.getY()
                                }
                            } else {
                                this.blocksAround = blocksAround
                                this.collisionBlocks = collidingBlocks
                            }
                        }

                        if (!this.isSpectator) {
                            checkNearEntities()
                        }

                        this.speed = from.subtract(to)
                    }

                    if (!revert && this.isFoodEnabled && this.server.difficulty > 0) {
                        hungerUpdate(distance)
                    }

                    if (revert) {
                        this.lastX = from.x
                        this.lastY = from.y
                        this.lastZ = from.z

                        this.lastYaw = from.yaw
                        this.lastPitch = from.pitch

                        this.x = from.x
                        this.y = from.y
                        this.z = from.z
                        this.yaw = from.yaw
                        this.pitch = from.pitch

                        this.sendPosition(from, from.yaw, from.pitch, MovePlayerPacket.MODE_TELEPORT)
                        forceMovement = Vector3(from.x, from.y, from.z)
                    } else {
                        forceMovement = null
                        if (distanceSquared != 0.0 && nextChunkOrderRun > 20) {
                            nextChunkOrderRun = 20
                        }
                    }
                }
            }
        }
    }

    private fun hungerUpdate(dist: Double) {
        with(this.p) {
            var distance = dist

            if (distance >= 0.05 && this.isFoodEnabled && this.server.difficulty > 0) {
                var jump = 0.0
                val swimming = if (this.isInsideOfWater) 0.01 * distance else 0.0
                if (swimming != 0.0) distance = 0.0
                if (this.isSprinting) {
                    if (inAirTicks == 3 && swimming == 0.0) {
                        jump = 0.2
                    }
                    foodData.updateFoodExpLevel(0.1 * distance + jump + swimming)
                } else {
                    if (inAirTicks == 3 && swimming == 0.0) {
                        jump = 0.05
                    }
                    foodData.updateFoodExpLevel(jump + swimming)
                }
            }
        }
    }

    override fun checkGroundState(large: Boolean) {
        with(this.p) {
            var onGround = false

            val realBB = this.boundingBox.clone()

            realBB.maxY = realBB.minY + 0.1
            realBB.minY -= 0.4

            if (large) {
                realBB.expand(0.2, 0.0, 0.2)
            }

            blocksUnder.clear()

            val bb = realBB.clone()
            bb.minY -= 0.6
            for (block in getBlocksUnder(bb)) {
                if (!onGround && !block.canPassThrough() && block.collidesWith(realBB)) {
                    onGround = true
                    break
                }

                if (block.id == BlockID.ICE) {
                    acData.collisionData.lastIcePos = currentPos
                }
            }

            this.onGround = onGround
            this.isCollided = this.onGround
        }
    }

    override fun getBlocksUnder(boundingBox: AxisAlignedBB?): List<Block> {
        with(this.p) {
            var bb = boundingBox

            if (blocksUnder.isEmpty()) {
                if (bb == null) {
                    bb = this.boundingBox.clone()
                    bb.maxY = bb.minY + 0.1
                    bb.minY -= 0.4
                }

                bb.forEach { x, y, z ->
                    blocksUnder.add(this.level.getBlock(x, y, z))
                }
            }

            return blocksUnder.toList()
        }
    }

    override fun setMotion(motion: Vector3): Boolean {
        with(this.p) {
            if (!this.justCreated && server.callEntityMotionEv) {
                val ev = EntityMotionEvent(this, motion)
                this.server.pluginManager.callEvent(ev)
                if (ev.isCancelled) {
                    return false
                }
            }

            this.motionX = motion.x
            this.motionY = motion.y
            this.motionZ = motion.z

            if (!this.justCreated) {
                this.updateMovement()
            }

            if (this.chunk != null && this.spawned) {
                this.addMotion(this.motionX, this.motionY, this.motionZ)
                val pk = SetEntityMotionPacket()
                pk.eid = this.id
                pk.motionX = motion.x.toFloat()
                pk.motionY = motion.y.toFloat()
                pk.motionZ = motion.z.toFloat()
                this.dataPacket(pk)
            }

            if (this.motionY > 0.0) {
                startAirTicks = (-ln(getGravity() / (getGravity() + getDrag() * this.motionY)) / getDrag() * 2 + 5).toInt()
            }

            return true
        }
    }

    private fun processPacketCheck(packetEntry: PacketCountCheck.Entry): Boolean {
        with(this.p) {
            ++packetEntry.packetCount
            val tick = this.server.tick
            if (tick - packetEntry.lastCheckTick >= 20) {
                val time = System.currentTimeMillis()

                val maxPackets = (time - packetEntry.lastCheckTime) / 50
                when {
                    packetEntry.packetCount > maxPackets * 2 -> packetEntry.revertCount += 3
                    packetEntry.packetCount.toDouble() > maxPackets.toDouble() * 1.5 -> packetEntry.revertCount += 2
                    packetEntry.packetCount.toDouble() > maxPackets.toDouble() * 1.25 -> ++packetEntry.revertCount
                    else -> packetEntry.revertCount = 0
                }

                packetEntry.packetCount = 0
                packetEntry.lastCheckTime = time
                packetEntry.lastCheckTick = tick
                if (packetEntry.revertCount > 6) {
                    GTAnticheat.instance.kickQueue[this.id] = "illegal speed"
                    return false
                }
            }

            return true
        }
    }

    override fun jump() {
        val cheatData = acData.antiCheatData

        val time = System.currentTimeMillis()
        debug { "jump" }

        cheatData.sinceJump = 0
        cheatData.lastJumpPos = p.location.clone()
        cheatData.lastJump = time
        //cheatData.setLastGroundPos(p.getLocation().clone());
        //cheatData.setLastOnGround(time);
    }
}