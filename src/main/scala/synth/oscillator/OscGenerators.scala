package synth.oscillator

import spinal.core._
import spinal.lib._
import synth.common.OscWaveforms

class OscGenerators extends Component {
  val io = new Bundle {
    val phase    = in UInt(24 bits)
    val pwmWidth = in UInt(8 bits)
    val waves    = out(OscWaveforms())
  }

  // Sawtooth: Use the top 16 bits of the phase.
  // We flip the MSB so the ramp starts at the negative peak (-32768) at phase 0,
  // providing a standard rising sawtooth waveform.
  io.waves.saw := (io.phase(23 downto 8) ^ 0x8000).asSInt

  // Square: Use MSB of the phase to toggle between positive and negative peaks.
  io.waves.square := Mux(io.phase(23), S(32767, 16 bits), S(-32768, 16 bits))

  // PWM: Compare phase against the expanded pulse width.
  // Per spec: expand 8-bit pwmWidth by multiplying by 4 (shift left 2).
  val expandedPwm = (io.pwmWidth << 16).resize(24 bits)
  io.waves.pwm := Mux(io.phase < expandedPwm, S(32767, 16 bits), S(-32768, 16 bits))

  // Triangle: Reflected phase arithmetic.
  // If MSB is 0: use lower 23 bits as a rising ramp.
  // If MSB is 1: bitwise invert lower 23 bits to create a falling ramp.
  // The result is shifted to 16 bits and cast to SInt for a smooth bipolar swing.
  val triReflected = UInt(23 bits)
  when(io.phase(23) === False) {
    triReflected := io.phase(22 downto 0)
  } otherwise {
    triReflected := ~io.phase(22 downto 0)
  }
  
  // Center the triangle wave by flipping the MSB.
  // This maps the 0..65535 range to -32768..32767 smoothly without jumps.
  io.waves.tri := (triReflected(22 downto 7) ^ 0x8000).asSInt
}
