package synth.uart

import spinal.core._
import spinal.lib._
import synth.common.{OscillatorConfig, EnvelopeConfig, FilterConfig}

class Uart extends Component {
  val io = new Bundle {
    val rx           = in Bool()
    val config       = out(OscillatorConfig())
    val envConfig    = out(EnvelopeConfig())
    val filterConfig = out(FilterConfig())
  }

  // Instantiate the internal submodules
  val rxModule        = new UartRx()
  val protocolDecoder = new UartProtocolDecoder()
  val registerBank    = new RegisterBank()

  // Internal connection logic
  rxModule.io.rx             := io.rx
  protocolDecoder.io.rxByte  << rxModule.io.byteOut
  registerBank.io.regWrite   << protocolDecoder.io.regWrite
  io.config                  := registerBank.io.config
  io.envConfig               := registerBank.io.envConfig
  io.filterConfig            := registerBank.io.filterConfig
}

