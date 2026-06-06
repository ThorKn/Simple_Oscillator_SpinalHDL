package synth.mixing

import spinal.core._
import spinal.lib._

class Attenuator(volumeWidth: Int = 8) extends Component {
  val io = new Bundle {
    val sampleIn  = slave(Flow(SInt(16 bits)))
    val volume    = in UInt(volumeWidth bits)
    val phaseTick = in Bool()
    val sampleOut = master(Flow(SInt(16 bits)))
  }

  // 1. Convert dynamic-width unsigned volume to a signed integer
  val volumeSigned = io.volume.intoSInt

  // 2. Multiplier (16-bit signed * (volumeWidth+1)-bit signed = (16+volumeWidth+1)-bit signed product)
  val product = io.sampleIn.payload * volumeSigned

  // 3. Scale down (divide by 2^volumeWidth) and resize to 16 bits
  val scaledSample = (product >> volumeWidth).resize(16 bits)

  // 4. Output registers and phaseTick‑aligned valid
  // Payload register (1‑sample latency)
  val outReg = Reg(SInt(16 bits)) init(0)
  when(io.sampleIn.valid) { outReg := scaledSample }
  io.sampleOut.payload := outReg

  // Track valid status aligned to phaseTick
  val validReg = Reg(Bool()) init(False)
  when(io.phaseTick) {
    validReg := io.sampleIn.valid
  }
  io.sampleOut.valid := io.phaseTick && validReg && !ClockDomain.current.isResetActive
}
