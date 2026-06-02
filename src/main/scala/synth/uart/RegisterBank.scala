package synth.uart

import spinal.core._
import spinal.lib._
import synth.common.{RegisterWrite, OscillatorConfig, EnvelopeConfig}

class RegisterBank extends Component {

  val io = new Bundle {
    val regWrite = slave(Flow(RegisterWrite()))
    val config   = out(OscillatorConfig())
    val envConfig = out(EnvelopeConfig())
  }

  // --------------------------------------------------------------------------
  // Raw Register Storage
  // --------------------------------------------------------------------------

  val freqLowReg      = Reg(Bits(8 bits)) init(0)
  val freqMidReg      = Reg(Bits(8 bits)) init(0)
  val freqHighReg     = Reg(Bits(8 bits)) init(0)
  
  // Staging registers for atomic commitment
  val freqLowShadow   = Reg(Bits(8 bits)) init(0)
  val freqMidShadow   = Reg(Bits(8 bits)) init(0)

  val waveformReg     = Reg(Bits(8 bits)) init(0)
  val pulseWidthReg   = Reg(Bits(8 bits)) init(0)
  val volumeReg       = Reg(Bits(8 bits)) init(0)

  // Envelope registers
  val envCtrlReg        = Reg(Bits(8 bits)) init(0)
  val envAttackReg      = Reg(Bits(8 bits)) init(0)
  val envDecayReg       = Reg(Bits(8 bits)) init(0)
  val envSustainReg     = Reg(Bits(8 bits)) init(0)
  val envReleaseReg     = Reg(Bits(8 bits)) init(0)
  val envGateReg        = Reg(Bits(8 bits)) init(0)

  // --------------------------------------------------------------------------
  // Register Write Logic
  // --------------------------------------------------------------------------

  when(io.regWrite.valid) {

    switch(io.regWrite.payload.address) {

      // Frequency Low (Stage in shadow register)
      is(U"8'x00") {
        freqLowShadow := io.regWrite.payload.data
      }

      // Frequency Mid (Stage in shadow register)
      is(U"8'x01") {
        freqMidShadow := io.regWrite.payload.data
      }

      // Frequency High (Trigger simultaneous atomic commit of High, Mid, and Low)
      is(U"8'x02") {
        freqHighReg := io.regWrite.payload.data
        freqMidReg  := freqMidShadow
        freqLowReg  := freqLowShadow
      }

      // Waveform
      is(U"8'x03") {
        waveformReg := io.regWrite.payload.data
      }

      // Pulse Width
      is(U"8'x04") {
        pulseWidthReg := io.regWrite.payload.data
      }

      // Volume
      is(U"8'x05") {
        volumeReg := io.regWrite.payload.data
      }

      // Envelope Control (ENV_CTRL)
      is(U"8'x40") {
        envCtrlReg := io.regWrite.payload.data
      }

      // Envelope Attack (ENV_ATTACK)
      is(U"8'x41") {
        envAttackReg := io.regWrite.payload.data
      }

      // Envelope Decay (ENV_DECAY)
      is(U"8'x42") {
        envDecayReg := io.regWrite.payload.data
      }

      // Envelope Sustain (ENV_SUSTAIN)
      is(U"8'x43") {
        envSustainReg := io.regWrite.payload.data
      }

      // Envelope Release (ENV_RELEASE)
      is(U"8'x44") {
        envReleaseReg := io.regWrite.payload.data
      }

      // Envelope Gate (ENV_GATE)
      is(U"8'x45") {
        envGateReg := io.regWrite.payload.data
      }
    }
  }

  // --------------------------------------------------------------------------
  // Frequency Assembly
  // --------------------------------------------------------------------------

  val frequencyCombined =
    (freqHighReg ## freqMidReg ## freqLowReg).asUInt

  // --------------------------------------------------------------------------
  // One-Cycle Synchronization Stage
  // --------------------------------------------------------------------------

  val oscFrequencyReg =
    RegNext(frequencyCombined) init(0)

  val oscWaveformReg =
    RegNext(waveformReg.asUInt) init(0)

  val oscPulseWidthReg =
    RegNext(pulseWidthReg.asUInt) init(0)

  val oscVolumeReg =
    RegNext(volumeReg.asUInt) init(0)

  // Synced envelope registers
  val syncedEnvCtrl        = RegNext(envCtrlReg) init(0)
  val syncedEnvAttack      = RegNext(envAttackReg.asUInt) init(0)
  val syncedEnvDecay       = RegNext(envDecayReg.asUInt) init(0)
  val syncedEnvSustain     = RegNext(envSustainReg.asUInt) init(0)
  val syncedEnvRelease     = RegNext(envReleaseReg.asUInt) init(0)
  val syncedEnvGate       = RegNext(envGateReg) init(0)

  // --------------------------------------------------------------------------
  // Outputs
  // --------------------------------------------------------------------------

  io.config.freqWord   := oscFrequencyReg
  io.config.waveSelect := oscWaveformReg(2 downto 0)
  io.config.pwmWidth   := oscPulseWidthReg
  io.config.volume     := oscVolumeReg

  io.envConfig.ctrl        := syncedEnvCtrl
  io.envConfig.attack      := syncedEnvAttack
  io.envConfig.decay       := syncedEnvDecay
  io.envConfig.sustain     := syncedEnvSustain
  io.envConfig.release     := syncedEnvRelease
  io.envConfig.gate        := syncedEnvGate
}