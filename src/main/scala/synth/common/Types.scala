package synth.common

import spinal.core._

// Represents an atomic register write transaction
case class RegisterWrite() extends Bundle {
  val address = UInt(8 bits)
  val data    = Bits(8 bits)
}

// ----- VOICE CONFIG: ------
// Bundle containing osc, env, and filter configs for one voice
case class VoiceConfig() extends Bundle {
  val osc    = OscConfig()
  val env    = EnvelopeConfig()
  val filter = FilterConfig()
}

// OSC CONFIG: Bundle sent to Oscillator
case class OscConfig() extends Bundle {
  val freqWord   = UInt(24 bits)
  val waveSelect = UInt(3 bits)
  val pwmWidth   = UInt(8 bits)
  val volume     = UInt(8 bits)
}

// ENV CONFIG: Bundle sent to Envelope Generator
case class EnvelopeConfig() extends Bundle {
  val ctrl        = Bits(8 bits)
  val attack      = UInt(8 bits)
  val decay       = UInt(8 bits)
  val sustain     = UInt(8 bits)
  val release     = UInt(8 bits)
  val gate        = Bits(8 bits)
}

// FILTER CONFIG: Bundle sent to SVF filter
case class FilterConfig() extends Bundle {
  val ctrl      = Bits(8 bits)
  val mode      = UInt(2 bits)
  val cutoff    = UInt(8 bits)
  val resonance = UInt(8 bits)
}

// WAVEFORMS: Bundle of waveform samples from Oscillators to Mux
case class OscWaveforms() extends Bundle {
  val saw    = SInt(16 bits)
  val square = SInt(16 bits)
  val pwm    = SInt(16 bits)
  val tri    = SInt(16 bits)
}

// Stage constants for the Envelope Generator FSM
object EnvelopeStage {
  val IDLE    = 0
  val ATTACK  = 1
  val DECAY   = 2
  val SUSTAIN = 3
  val RELEASE = 4
}