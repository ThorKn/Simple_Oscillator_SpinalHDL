package synth.uart

import spinal.core._
import spinal.lib._
import synth.common.VoiceConfig

class Uart extends Component {
  val io = new Bundle {
    val rx           = in Bool()
    val voiceConfig  = out(VoiceConfig())
  }

  // Instantiate the internal submodules
  val rxModule        = new UartRx()
  val protocolDecoder = new UartProtocolDecoder()
  val registerBank    = new RegisterBank()

  // Internal connection logic
  rxModule.io.rx             := io.rx
  protocolDecoder.io.rxByte  << rxModule.io.byteOut
  registerBank.io.regWrite   << protocolDecoder.io.regWrite
  io.voiceConfig             := registerBank.io.voiceConfig
}

