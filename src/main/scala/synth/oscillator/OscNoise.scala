package synth.oscillator

import spinal.core._
import spinal.lib._

class OscNoise extends Component {
  val io = new Bundle {
    val phaseTick = in Bool()
    val sample    = out SInt(16 bits)
  }

  // 23-bit Fibonacci LFSR register.
  // Initialized to 1 to prevent the "all-zeros" lock-up state.
  val lfsr = Reg(UInt(23 bits)) init(1)

  // Feedback bit for x^23 + x^18 + 1.
  // We XOR bit 22 (the bit shifted out) and bit 17.
  val feedback = lfsr(22) ^ lfsr(17)

  // Shift left and insert the feedback bit at the LSB on every phaseTick.
  when(io.phaseTick) {
    lfsr := (lfsr(21 downto 0) ## feedback).asUInt
  }

  // Output the top 16 bits reinterpreted as a signed integer for the noise sample.
  io.sample := lfsr(22 downto 7).asSInt
}
