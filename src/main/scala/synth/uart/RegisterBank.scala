package synth.uart

import spinal.core._
import spinal.lib._
import synth.common.{RegisterWrite, VoiceConfig}

class RegisterBank extends Component {

  val io = new Bundle {
    val regWrite        = slave(Flow(RegisterWrite()))
    val voiceConfig     = out(VoiceConfig())
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
  val envCtrlReg           = Reg(Bits(8 bits)) init(0)
  val envAttackReg         = Reg(Bits(8 bits)) init(0)
  val envDecayReg          = Reg(Bits(8 bits)) init(0)
  val envSustainReg        = Reg(Bits(8 bits)) init(0)
  val envReleaseReg        = Reg(Bits(8 bits)) init(0)
  val envGateReg           = Reg(Bits(8 bits)) init(0)

  // Filter registers
  val filterCtrlReg        = Reg(Bits(8 bits)) init(0)
  val filterModeReg        = Reg(Bits(8 bits)) init(0)
  val filterCutoffReg      = Reg(Bits(8 bits)) init(0)
  val filterResReg         = Reg(Bits(8 bits)) init(0)

  // --------------------------------------------------------------------------
  // Register Write Logic
  // --------------------------------------------------------------------------

  when(io.regWrite.valid) {

    switch(io.regWrite.payload.address) {

      // Frequency Low (Stage in shadow register) (OSC_FREQ_LOW)
      is(U"8'x30") {
        oscFreqLowShadow := io.regWrite.payload.data
      }

      // Frequency Mid (Stage in shadow register) (OSC_FREQ_MID)
      is(U"8'x31") {
        oscFreqMidShadow := io.regWrite.payload.data
      }

      // Frequency High (Trigger simultaneous atomic commit of High, Mid, and Low) (OSC_FREQ_HIGH)
      is(U"8'x32") {
        oscFreqHighReg := io.regWrite.payload.data
        oscFreqMidReg  := oscFreqMidShadow
        oscFreqLowReg  := oscFreqLowShadow
      }

      // Waveform (OSC_WAVE_SEL)
      is(U"8'x33") {
        oscWaveformReg := io.regWrite.payload.data
      }

      // Pulse Width (OSC_PWM_WIDTH)
      is(U"8'x34") {
        oscPulseWidthReg := io.regWrite.payload.data
      }

      // Volume (OSC_VOLUME)
      is(U"8'x35") {
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
  val syncedOscFreqWord        = RegNext(oscFrequencyCombined) init(0)
  val syncedOscWaveSelect      = RegNext(oscWaveformReg.asUInt) init(0)
  val syncedOscPwmWidth        = RegNext(oscPulseWidthReg.asUInt) init(0)
  val syncedOscVolume          = RegNext(oscVolumeReg.asUInt) init(0)

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

  io.voiceConfig.osc.freqWord     := syncedOscFreqWord
  io.voiceConfig.osc.waveSelect   := syncedOscWaveSelect(2 downto 0)
  io.voiceConfig.osc.pwmWidth     := syncedOscPwmWidth
  io.voiceConfig.osc.volume       := syncedOscVolume

  io.voiceConfig.env.ctrl         := syncedEnvCtrl
  io.voiceConfig.env.attack       := syncedEnvAttack
  io.voiceConfig.env.decay        := syncedEnvDecay
  io.voiceConfig.env.sustain      := syncedEnvSustain
  io.voiceConfig.env.release      := syncedEnvRelease
  io.voiceConfig.env.gate         := syncedEnvGate

  io.voiceConfig.filter.ctrl      := syncedFilterCtrl
  io.voiceConfig.filter.mode      := syncedFilterMode(1 downto 0)
  io.voiceConfig.filter.cutoff    := syncedFilterCutoff
  io.voiceConfig.filter.resonance := syncedFilterRes
}