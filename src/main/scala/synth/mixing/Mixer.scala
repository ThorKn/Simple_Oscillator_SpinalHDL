package synth.mixing

import spinal.core._
import spinal.core.sim._
import spinal.lib._

class Mixer(numVoices: Int = 1) extends Component {
  require(numVoices >= 1 && numVoices <= 7, "Mixer supports 1 to 7 input channels")

  val io = new Bundle {
    val inputs    = Vec(slave(Flow(SInt(16 bits))), numVoices)
    val mixerCtrl = in Bits(8 bits)
    val phaseTick = in Bool()
    val sampleOut = master(Flow(SInt(16 bits)))
  }

  // 1. Mute Logic (0 = ON/Un-muted, 1 = OFF/Muted)
  val activeInputs = Vec(SInt(16 bits), numVoices)
  for (v <- 0 until numVoices) {
    activeInputs(v) := io.inputs(v).payload
    when(io.mixerCtrl(v)) {
      activeInputs(v) := 0
    }
  }

  // 2. Accumulation with Guard Bits to prevent overflow
  val accWidth = 16 + log2Up(numVoices)
  val acc = SInt(accWidth bits)
  acc := activeInputs.map(_.resize(accWidth)).reduce(_ + _)

  // 3. Manual Saturation/Clamping to 16-bit signed limits
  val saturated = SInt(16 bits)
  when(acc > 32767) {
    saturated := 32767
  } elsewhen(acc < -32768) {
    saturated := -32768
  } otherwise {
    saturated := acc.resize(16 bits)
  }

  // 4. Output Buffering (1-cycle phaseTick latency)
  val outReg = Reg(SInt(16 bits)).init(0).simPublic()
  when(io.inputs(0).valid) {
    outReg := saturated
  }
  io.sampleOut.payload := outReg

  val validReg = Reg(Bool()).init(False).simPublic()
  when(io.phaseTick) {
    validReg := io.inputs(0).valid
  }
  io.sampleOut.valid := io.phaseTick && validReg && !ClockDomain.current.isResetActive
}
