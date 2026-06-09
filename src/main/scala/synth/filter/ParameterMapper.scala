package synth.filter

import spinal.core._
import spinal.lib._
import synth.common.RomData

class ParameterMapper extends Component {
  val io = new Bundle {
    val cutoff         = in UInt(8 bits)
    val resonance      = in UInt(8 bits)
    val cutoffCoeff    = out UInt(12 bits)
    val resonanceCoeff = out UInt(8 bits)
  }

  // 1. Generate Exponential Cutoff ROM (256 x 12 bits)
  val cutoffRom = Mem(UInt(12 bits), 256) init(RomData.filterCutoffLut.map(U(_, 12 bits)))

  // 2. Generate Quadratic Resonance ROM (256 x 8 bits)
  val resonanceRom = Mem(UInt(8 bits), 256) init(RomData.filterResonanceLut.map(U(_, 8 bits)))

  // 3. Combinational Read
  io.cutoffCoeff    := cutoffRom.readAsync(io.cutoff)
  io.resonanceCoeff := resonanceRom.readAsync(io.resonance)
}

