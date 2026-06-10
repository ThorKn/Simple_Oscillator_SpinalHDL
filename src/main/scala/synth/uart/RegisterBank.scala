package synth.uart

import spinal.core._
import spinal.lib._
import synth.common.{RegisterWrite, OscConfig, EnvelopeConfig, FilterConfig}

class RegisterBank extends Component {

  val io = new Bundle {
    val regWrite        = slave(Flow(RegisterWrite()))
    val oscConfig       = out(OscConfig())
    val envConfig       = out(EnvelopeConfig())
    val filterConfig    = out(FilterConfig())
  }

  // --------------------------------------------------------------------------
  // Raw Register Storage
  // --------------------------------------------------------------------------

  // Oscillator registers
  val oscFreqLowReg        = Reg(Bits(8 bits)) init(0)
  val oscFreqMidReg        = Reg(Bits(8 bits)) init(0)
  val oscFreqHighReg       = Reg(Bits(8 bits)) init(0)
  val oscWaveformReg       = Reg(Bits(8 bits)) init(0)
  val oscPulseWidthReg     = Reg(Bits(8 bits)) init(0)
  val oscVolumeReg         = Reg(Bits(8 bits)) init(0)
  
  // Staging registers for atomic commitment to frequency reg
  val oscFreqLowShadow     = Reg(Bits(8 bits)) init(0)
  val oscFreqMidShadow     = Reg(Bits(8 bits)) init(0)

  // Envelope registers
  val envCtrlReg        = Reg(Bits(8 bits)) init(0)
  val envAttackReg      = Reg(Bits(8 bits)) init(0)
  val envDecayReg       = Reg(Bits(8 bits)) init(0)
  val envSustainReg     = Reg(Bits(8 bits)) init(0)
  val envReleaseReg     = Reg(Bits(8 bits)) init(0)
  val envGateReg        = Reg(Bits(8 bits)) init(0)

  // Filter registers
  val filterCtrlReg     = Reg(Bits(8 bits)) init(0)
  val filterModeReg     = Reg(Bits(8 bits)) init(0)
  val filterCutoffReg   = Reg(Bits(8 bits)) init(0)
  val filterResReg      = Reg(Bits(8 bits)) init(0)

  // --------------------------------------------------------------------------
  // Register Write Logic
  // --------------------------------------------------------------------------

  when(io.regWrite.valid) {

    switch(io.regWrite.payload.address) {

      // Frequency Low (Stage in shadow register) (OSC_FREQ_LOW)
      is(U"8'x00") {
        oscFreqLowShadow := io.regWrite.payload.data
      }

      // Frequency Mid (Stage in shadow register) (OSC_FREQ_MID)
      is(U"8'x01") {
        oscFreqMidShadow := io.regWrite.payload.data
      }

      // Frequency High (Trigger simultaneous atomic commit of High, Mid, and Low) (OSC_FREQ_HIGH)
      is(U"8'x02") {
        oscFreqHighReg := io.regWrite.payload.data
        oscFreqMidReg  := oscFreqMidShadow
        oscFreqLowReg  := oscFreqLowShadow
      }

      // Waveform (OSC_WAVE_SEL)
      is(U"8'x03") {
        oscWaveformReg := io.regWrite.payload.data
      }

      // Pulse Width (OSC_PWM_WIDTH)
      is(U"8'x04") {
        oscPulseWidthReg := io.regWrite.payload.data
      }

      // Volume (OSC_VOLUME)
      is(U"8'x05") {
        oscVolumeReg := io.regWrite.payload.data
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

      // Filter Control (FILTER_CTRL)
      is(U"8'x50") {
        filterCtrlReg := io.regWrite.payload.data
      }

      // Filter Mode (FILTER_MODE)
      is(U"8'x51") {
        filterModeReg := io.regWrite.payload.data
      }

      // Filter Cutoff (FILTER_CUTOFF)
      is(U"8'x52") {
        filterCutoffReg := io.regWrite.payload.data
      }

      // Filter Resonance (FILTER_RESONANCE)
      is(U"8'x53") {
        filterResReg := io.regWrite.payload.data
      }
    }
  }

  // --------------------------------------------------------------------------
  // Frequency Assembly
  // --------------------------------------------------------------------------

  val oscFrequencyCombined = (oscFreqHighReg ## oscFreqMidReg ## oscFreqLowReg).asUInt

  // --------------------------------------------------------------------------
  // One-Cycle Synchronization Stage
  // --------------------------------------------------------------------------

  // Synced oscillator registers
  val syncedOscFreqWord      = RegNext(oscFrequencyCombined) init(0)
  val syncedOscWaveSelect    = RegNext(oscWaveformReg.asUInt) init(0)
  val syncedOscPwmWidth      = RegNext(oscPulseWidthReg.asUInt) init(0)
  val syncedOscVolume        = RegNext(oscVolumeReg.asUInt) init(0)

  // Synced envelope registers
  val syncedEnvCtrl            = RegNext(envCtrlReg) init(0)
  val syncedEnvAttack          = RegNext(envAttackReg.asUInt) init(0)
  val syncedEnvDecay           = RegNext(envDecayReg.asUInt) init(0)
  val syncedEnvSustain         = RegNext(envSustainReg.asUInt) init(0)
  val syncedEnvRelease         = RegNext(envReleaseReg.asUInt) init(0)
  val syncedEnvGate            = RegNext(envGateReg) init(0)

  // Synced filter registers
  val syncedFilterCtrl         = RegNext(filterCtrlReg) init(0)
  val syncedFilterMode         = RegNext(filterModeReg.asUInt) init(0)
  val syncedFilterCutoff       = RegNext(filterCutoffReg.asUInt) init(0)
  val syncedFilterRes          = RegNext(filterResReg.asUInt) init(0)

  // --------------------------------------------------------------------------
  // Outputs
  // --------------------------------------------------------------------------

  io.oscConfig.freqWord        := syncedOscFreqWord
  io.oscConfig.waveSelect      := syncedOscWaveSelect(2 downto 0)
  io.oscConfig.pwmWidth        := syncedOscPwmWidth
  io.oscConfig.volume          := syncedOscVolume

  io.envConfig.ctrl         := syncedEnvCtrl
  io.envConfig.attack       := syncedEnvAttack
  io.envConfig.decay        := syncedEnvDecay
  io.envConfig.sustain      := syncedEnvSustain
  io.envConfig.release      := syncedEnvRelease
  io.envConfig.gate         := syncedEnvGate

  io.filterConfig.ctrl      := syncedFilterCtrl
  io.filterConfig.mode      := syncedFilterMode(1 downto 0)
  io.filterConfig.cutoff    := syncedFilterCutoff
  io.filterConfig.resonance := syncedFilterRes
}