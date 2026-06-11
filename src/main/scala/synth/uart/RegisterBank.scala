package synth.uart

import spinal.core._
import spinal.lib._
import synth.common._

class RegisterBank(numVoices: Int = 1) extends Component {

  val io = new Bundle {
    val regWrite    = slave(Flow(RegisterWrite()))
    val synthConfig = out(SynthConfig())
    val voiceConfig = out(Vec(VoiceConfig(), numVoices))
  }

  // --------------------------------------------------------------------------
  // Raw Register Storage
  // --------------------------------------------------------------------------

  // Global registers
  val mixerCtrlReg = Reg(Bits(8 bits)) init(0)

  // Voice registers structure
  class VoiceRegs extends Area {
    val oscFreqLow      = Reg(Bits(8 bits)) init(0)
    val oscFreqMid      = Reg(Bits(8 bits)) init(0)
    val oscFreqHigh     = Reg(Bits(8 bits)) init(0)
    val oscWaveform     = Reg(Bits(8 bits)) init(0)
    val oscPulseWidth   = Reg(Bits(8 bits)) init(0)
    val oscVolume       = Reg(Bits(8 bits)) init(0)

    // Staging registers for atomic commitment to frequency register
    val freqLowShadow   = Reg(Bits(8 bits)) init(0)
    val freqMidShadow   = Reg(Bits(8 bits)) init(0)

    // Envelope registers
    val envCtrl         = Reg(Bits(8 bits)) init(0)
    val envAttack       = Reg(Bits(8 bits)) init(0)
    val envDecay        = Reg(Bits(8 bits)) init(0)
    val envSustain      = Reg(Bits(8 bits)) init(0)
    val envRelease      = Reg(Bits(8 bits)) init(0)
    val envGate         = Reg(Bits(8 bits)) init(0)

    // Filter registers
    val filterCtrl      = Reg(Bits(8 bits)) init(0)
    val filterMode      = Reg(Bits(8 bits)) init(0)
    val filterCutoff    = Reg(Bits(8 bits)) init(0)
    val filterRes       = Reg(Bits(8 bits)) init(0)
  }

  val voices = Seq.fill(numVoices)(new VoiceRegs())

  // --------------------------------------------------------------------------
  // Register Write Logic
  // --------------------------------------------------------------------------

  when(io.regWrite.valid) {

    // 1. Global Synth Configuration writes (range 0x00 to 0x0F)
    when(io.regWrite.payload.address < 0x10) {
      val synthMappings = Seq(
        SynthRegisterOffsets.MIXER_CTRL -> mixerCtrlReg
      )
      for ((addr, reg) <- synthMappings) {
        when(io.regWrite.payload.address === addr) {
          reg := io.regWrite.payload.data
        }
      }
    }

    // 2. Voice-specific register writes (base address 0x10 + v * 0x20)
    for (v <- 0 until numVoices) {
      val voiceBase = 0x10 + (v * 0x20)
      val voiceEnd  = voiceBase + 24
      
      when(io.regWrite.payload.address >= voiceBase && io.regWrite.payload.address <= voiceEnd) {
        val offset = io.regWrite.payload.address - voiceBase

        val voiceMappings = Seq(
          VoiceRegisterOffsets.OSC_FREQ_LOW     -> voices(v).freqLowShadow,
          VoiceRegisterOffsets.OSC_FREQ_MID     -> voices(v).freqMidShadow,
          VoiceRegisterOffsets.OSC_WAVE_SEL     -> voices(v).oscWaveform,
          VoiceRegisterOffsets.OSC_PWM_WIDTH    -> voices(v).oscPulseWidth,
          VoiceRegisterOffsets.OSC_VOLUME       -> voices(v).oscVolume,
          VoiceRegisterOffsets.ENV_CTRL          -> voices(v).envCtrl,
          VoiceRegisterOffsets.ENV_ATTACK        -> voices(v).envAttack,
          VoiceRegisterOffsets.ENV_DECAY         -> voices(v).envDecay,
          VoiceRegisterOffsets.ENV_SUSTAIN       -> voices(v).envSustain,
          VoiceRegisterOffsets.ENV_RELEASE       -> voices(v).envRelease,
          VoiceRegisterOffsets.ENV_GATE          -> voices(v).envGate,
          VoiceRegisterOffsets.FILTER_CTRL       -> voices(v).filterCtrl,
          VoiceRegisterOffsets.FILTER_MODE       -> voices(v).filterMode,
          VoiceRegisterOffsets.FILTER_CUTOFF     -> voices(v).filterCutoff,
          VoiceRegisterOffsets.FILTER_RESONANCE  -> voices(v).filterRes
        )

        for ((off, reg) <- voiceMappings) {
          when(offset === off) {
            reg := io.regWrite.payload.data
          }
        }

        // Special atomic commit for frequency high byte
        when(offset === VoiceRegisterOffsets.OSC_FREQ_HIGH) {
          voices(v).oscFreqHigh := io.regWrite.payload.data
          voices(v).oscFreqMid  := voices(v).freqMidShadow
          voices(v).oscFreqLow  := voices(v).freqLowShadow
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // Output Synchronization (RegNext)
  // --------------------------------------------------------------------------

  // Global Config Sync
  io.synthConfig.mixerCtrl := RegNext(mixerCtrlReg) init(0)

  // Voice Configs Sync
  for (v <- 0 until numVoices) {
    val oscCombinedFreq = (voices(v).oscFreqHigh ## voices(v).oscFreqMid ## voices(v).oscFreqLow).asUInt
    
    io.voiceConfig(v).osc.freqWord     := RegNext(oscCombinedFreq) init(0)
    io.voiceConfig(v).osc.waveSelect   := RegNext(voices(v).oscWaveform.asUInt(2 downto 0)) init(0)
    io.voiceConfig(v).osc.pwmWidth     := RegNext(voices(v).oscPulseWidth.asUInt) init(0)
    io.voiceConfig(v).osc.volume       := RegNext(voices(v).oscVolume.asUInt) init(0)

    io.voiceConfig(v).env.ctrl         := RegNext(voices(v).envCtrl) init(0)
    io.voiceConfig(v).env.attack       := RegNext(voices(v).envAttack.asUInt) init(0)
    io.voiceConfig(v).env.decay        := RegNext(voices(v).envDecay.asUInt) init(0)
    io.voiceConfig(v).env.sustain      := RegNext(voices(v).envSustain.asUInt) init(0)
    io.voiceConfig(v).env.release      := RegNext(voices(v).envRelease.asUInt) init(0)
    io.voiceConfig(v).env.gate         := RegNext(voices(v).envGate) init(0)

    io.voiceConfig(v).filter.ctrl      := RegNext(voices(v).filterCtrl) init(0)
    io.voiceConfig(v).filter.mode      := RegNext(voices(v).filterMode.asUInt(1 downto 0)) init(0)
    io.voiceConfig(v).filter.cutoff    := RegNext(voices(v).filterCutoff.asUInt) init(0)
    io.voiceConfig(v).filter.resonance := RegNext(voices(v).filterRes.asUInt) init(0)
  }
}