package synth.envelope

import spinal.core._
import spinal.core.sim._
import spinal.lib._

class EnvelopeShaper extends Component {
  val io = new Bundle {
    val phaseTick    = in Bool()              // Heartbeat tick synced with 480 kHz sample rate
    val baseIndex    = in UInt(8 bits)        // Upper 8 integer bits from accumulator
    val fraction     = in UInt(2 bits)        // Lower 2 fractional bits from accumulator
    val curveSelect  = in UInt(2 bits)        // 00=Lin, 01=Exp, 10=Log, 11=S-Curve
    val sustainLevel = in UInt(8 bits)        // Target sustain level (0 to 255)
    val activeStage  = in UInt(3 bits)        // Active FSM stage indicator (IDLE=0, ATTACK=1, DECAY=2, SUSTAIN=3, RELEASE=4)
    val accumDir     = in Bool()              // Direction of accumulator (0 = Forward, 1 = Reverse)
    val envelopeOut       = master(Flow(UInt(10 bits))) // 10-bit unipolar flow (0 to 1023)
    val envelopeOutSigned = master(Flow(SInt(10 bits))) // 10-bit bipolar flow (-512 to +511)
  }


  // -------------------------------------------------------------------------
  // 1. Compile-Time ROM Contents Generation (257 entries each)
  // -------------------------------------------------------------------------
  
  // 1.1 Linear ROM: y = x (safely clamped to 255 at index 256 for interpolation boundary)
  val linContent = for (x <- 0 to 256) yield {
    U(scala.math.min(255, x), 8 bits)
  }

  // 1.2 Exponential ROM: y = 255 * (exp(3 * x / 255) - 1) / (exp(3) - 1)
  val expContent = for (x <- 0 to 256) yield {
    val factor = scala.math.min(255.0, x.toDouble) / 255.0
    val v = 255.0 * (scala.math.exp(3.0 * factor) - 1.0) / (scala.math.exp(3.0) - 1.0)
    U(scala.math.round(v).toInt, 8 bits)
  }

  // 1.3 Logarithmic ROM: y = 255 * log1p(7 * x / 255) / log1p(7)
  val logContent = for (x <- 0 to 256) yield {
    val factor = scala.math.min(255.0, x.toDouble) / 255.0
    val v = 255.0 * scala.math.log1p(7.0 * factor) / scala.math.log1p(7.0)
    U(scala.math.round(v).toInt, 8 bits)
  }

  // 1.4 Sigmoid (S-Curve) ROM: y = 255 * (1 - cos(pi * x / 255)) / 2
  val sigContent = for (x <- 0 to 256) yield {
    val factor = scala.math.min(255.0, x.toDouble) / 255.0
    val v = 255.0 * (1.0 - scala.math.cos(scala.math.Pi * factor)) / 2.0
    U(scala.math.round(v).toInt, 8 bits)
  }

  // -------------------------------------------------------------------------
  // 2. ROM Table Instantiations
  // -------------------------------------------------------------------------
  val linRom = Mem(UInt(8 bits), 257) init(linContent)
  val expRom = Mem(UInt(8 bits), 257) init(expContent)
  val logRom = Mem(UInt(8 bits), 257) init(logContent)
  val sigRom = Mem(UInt(8 bits), 257) init(sigContent)

  // -------------------------------------------------------------------------
  // 3. Lookup Address & Boundary Values Selection Mux
  // -------------------------------------------------------------------------
  // Resize to 9 bits so that (baseIndex + 1) safely reaches index 256 without wrapping
  val addr0 = io.baseIndex.resize(9 bits)
  val addr1 = io.baseIndex.resize(9 bits) + 1

  val y0 = UInt(8 bits).simPublic()
  val y1 = UInt(8 bits).simPublic()

  // Default assignments
  y0 := 0
  y1 := 0

  switch(io.curveSelect) {
    is(0) { // Linear
      y0 := linRom.readAsync(addr0)
      y1 := linRom.readAsync(addr1)
    }
    is(1) { // Exponential
      y0 := expRom.readAsync(addr0)
      y1 := expRom.readAsync(addr1)
    }
    is(2) { // Logarithmic
      y0 := logRom.readAsync(addr0)
      y1 := logRom.readAsync(addr1)
    }
    is(3) { // Sigmoid (S-Curve)
      y0 := sigRom.readAsync(addr0)
      y1 := sigRom.readAsync(addr1)
    }
  }

  // Safely zero-extend to 9-bit SInt to prevent signed casting MSB sign-bit bugs
  val y0Signed = y0.intoSInt
  val y1Signed = y1.intoSInt

  // -------------------------------------------------------------------------
  // 4. Multiplierless Shift-Add Linear Interpolation Math
  // -------------------------------------------------------------------------
  // Compute signed delta: Y1 - Y0 (10-bit signed SInt)
  val delta = y1Signed - y0Signed

  // interp will hold (Y0 * 4 + f * delta), representing the 10-bit unipolar value
  val interp = SInt(12 bits)

  // Pre-shifted terms for clean hardware generation
  val y0Shifted = (y0Signed << 2).resize(12 bits) // Y0 * 4
  val deltaShifted = (delta << 1).resize(12 bits) // 2 * delta
  val deltaResized = delta.resize(12 bits)

  // Swap interpolation fraction direction based on accumDir when counting backwards
  val fractionAdjusted = io.accumDir ? (U(3) - io.fraction) | io.fraction

  switch(fractionAdjusted) {
    is(0) { interp := y0Shifted }
    is(1) { interp := y0Shifted + deltaResized }
    is(2) { interp := y0Shifted + deltaShifted }
    is(3) { interp := y0Shifted + deltaShifted + deltaResized }
  }

  // Convert to 10-bit unsigned unipolar value (0 to 1023)
  val finalValUnipolar = interp.asUInt.resize(10 bits).simPublic()

  // -------------------------------------------------------------------------
  // 5. Parallel Output Conversion & Flow Gating (1-Cycle Latency Pipeline)
  // -------------------------------------------------------------------------
  // Unipolar Output Stage (0 to 1023) - Routed directly since FSM naturally holds baseIndex on Sustain
  io.envelopeOut.payload := RegNext(finalValUnipolar) init(0)

  // Bipolar Output Stage (-512 to +511)
  // Invert the MSB to map 0 to 1023 unipolar to -512 to 511 signed center-zero combinationally
  val finalValBipolar = (finalValUnipolar ^ 0x200).asSInt
  io.envelopeOutSigned.payload := RegNext(finalValBipolar) init(0)

  // Heartbeat valid flow gating qualified with active reset check
  val outValid = RegNext(io.phaseTick && !ClockDomain.current.isResetActive) init(False)
  io.envelopeOut.valid       := outValid
  io.envelopeOutSigned.valid := outValid
}
