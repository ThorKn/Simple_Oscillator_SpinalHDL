package synth.output

import spinal.core._
import spinal.lib._

class I2STransmitter extends Component {
  val io = new Bundle {
    val sampleIn = slave(Flow(SInt(16 bits)))
    val bclk     = out Bool()
    val lrclk    = out Bool()
    val sdata    = out Bool()
  }

  // Timing Pattern Table: 16, 16, 15, 16, 16, 15, 16, 15 clock cycles per bit.
  // Average is 15.625 cycles per bit. 32 bits * 15.625 = 500 clock cycles (48 kHz).
  val patternTable = Vec(U(16, 5 bits), U(16, 5 bits), U(15, 5 bits), U(16, 5 bits),
                         U(16, 5 bits), U(15, 5 bits), U(16, 5 bits), U(15, 5 bits))

  val cycleCounter = Reg(UInt(5 bits)) init(15)
  val patternIndex = Reg(UInt(3 bits)) init(0)
  val bitCounter   = Reg(UInt(5 bits)) init(0)
  val shiftReg     = Reg(Bits(32 bits)) init(0)
  val sampleBuffer = Reg(SInt(16 bits)) init(0)
  val active       = Reg(Bool()) init(False)

  // Sync / Latch input sample on valid from Decimator
  when(io.sampleIn.valid) {
    sampleBuffer := io.sampleIn.payload
    // If not currently transmitting, start immediately
    when(!active) {
      active       := True
      bitCounter   := 0
      patternIndex := 0
      cycleCounter := patternTable(0) - 1
    }
  }

  // Bit timer & shifting data path
  when(active) {
    when(cycleCounter === 0) {
      // Bit Boundary (BCLK falling edge)
      val nextPatternIndex = (patternIndex + 1).resize(3)
      val nextBit = (bitCounter + 1).resize(5)

      patternIndex := nextPatternIndex
      cycleCounter := patternTable(nextPatternIndex) - 1
      bitCounter   := nextBit

      // Reload shift register at the end of Slot 0
      when(bitCounter === 0) {
        shiftReg := sampleBuffer.asBits ## sampleBuffer.asBits
      } otherwise {
        shiftReg := (shiftReg << 1).resize(32)
      }
    } otherwise {
      cycleCounter := cycleCounter - 1
    }
  }

  // BCLK Generation: High for the second half of the bit duration (rising edge in the middle).
  // Driven LOW at cycle 0 (falling edge) to launch stable data.
  io.bclk := active && (cycleCounter < 8)

  // LRCLK Generation: LOW for Left channel (slots 0 to 15), HIGH for Right channel (slots 16 to 31)
  io.lrclk := !active || (bitCounter >= 16)

  // SDATA output: MSB-first
  io.sdata := active && shiftReg(31)
}
