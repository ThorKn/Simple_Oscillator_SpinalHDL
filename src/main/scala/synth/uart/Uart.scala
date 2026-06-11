package synth.uart

import spinal.core._
import spinal.lib._
import synth.common.{SynthConfig, VoiceConfig}

class Uart(numVoices: Int = 1) extends Component {
  val io = new Bundle {
    val rx           = in Bool()
    val synthConfig  = out(SynthConfig())
    val voiceConfig  = out(Vec(VoiceConfig(), numVoices))
  }

  // Instantiate the internal submodules
  val rxModule        = new UartRx()
  val protocolDecoder = new UartProtocolDecoder()
  val registerBank    = new RegisterBank(numVoices)

  // Internal connection logic
  rxModule.io.rx             := io.rx
  protocolDecoder.io.rxByte  << rxModule.io.byteOut
  registerBank.io.regWrite   << protocolDecoder.io.regWrite
  io.synthConfig             := registerBank.io.synthConfig
  io.voiceConfig             := registerBank.io.voiceConfig
}

