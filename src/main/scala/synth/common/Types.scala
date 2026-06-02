package synth.common

import spinal.core._

// Represents an atomic register write transaction
case class RegisterWrite() extends Bundle {
  val address = UInt(8 bits)
  val data    = Bits(8 bits)
}

// Unified configuration bundle sent from Register Bank to the Oscillator
case class OscillatorConfig() extends Bundle {
  val freqWord   = UInt(24 bits)
  val waveSelect = UInt(3 bits)
  val pwmWidth   = UInt(8 bits)
  val volume     = UInt(8 bits)
}

// Unified waveforms bundle sent from Generators to Mux
case class Waveforms() extends Bundle {
  val saw    = SInt(16 bits)
  val square = SInt(16 bits)
  val pwm    = SInt(16 bits)
  val tri    = SInt(16 bits)
}

// Unified configuration bundle sent from Register Bank to the Envelope Generator
case class EnvelopeConfig() extends Bundle {
  val ctrl        = Bits(8 bits)
  val attack      = UInt(8 bits)
  val decay       = UInt(8 bits)
  val sustain     = UInt(8 bits)
  val release     = UInt(8 bits)
  val gate        = Bits(8 bits)
}

// Stage constants for the Envelope Generator FSM
object EnvelopeStage {
  val IDLE    = 0
  val ATTACK  = 1
  val DECAY   = 2
  val SUSTAIN = 3
  val RELEASE = 4
}


