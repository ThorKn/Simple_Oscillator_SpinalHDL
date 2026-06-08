package synth.filter

import spinal.core._
import spinal.lib._

class ParameterMapper extends Component {
  val io = new Bundle {
    val cutoff         = in UInt(8 bits)
    val resonance      = in UInt(8 bits)
    val cutoffCoeff    = out UInt(12 bits)
    val resonanceCoeff = out UInt(8 bits)
  }

  // 1. Generate Exponential Cutoff ROM (256 x 12 bits)
  val cutoffRomData = for (p <- 0 until 256) yield {
    val coeffVal = Math.round(10.0 * Math.pow(4095.0 / 10.0, p / 255.0)).toInt
    U(coeffVal, 12 bits)
  }
  val cutoffRom = Mem(UInt(12 bits), 256) init(cutoffRomData)

  // 2. Generate Quadratic Resonance ROM (256 x 8 bits)
  val resonanceRomData = for (r <- 0 until 256) yield {
    val coeffVal = Math.round(255.0 - 251.0 * Math.pow(r / 255.0, 2.0)).toInt
    U(coeffVal, 8 bits)
  }
  val resonanceRom = Mem(UInt(8 bits), 256) init(resonanceRomData)

  // 3. Combinational Read
  io.cutoffCoeff    := cutoffRom.readAsync(io.cutoff)
  io.resonanceCoeff := resonanceRom.readAsync(io.resonance)
}
