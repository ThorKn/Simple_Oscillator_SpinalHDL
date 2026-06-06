package synth

import spinal.core._
import spinal.lib._

import synth.uart._
import synth.oscillator.Oscillator
import synth.envelope.EnvelopeGenerator
import synth.filter.SVF
import synth.output._
import synth.mixing.Attenuator
import synth.timing.TimingGenerator
import synth.common._

class Synth extends Component {

  val io = new Bundle {
    val clk24MHz = in Bool()
    val reset    = in Bool()

    val uartRx   = in Bool()

    val i2sBclk  = out Bool()
    val i2sLrclk = out Bool()
    val i2sData  = out Bool()
  }

  // Map the external clk and reset pins to the internal ClockDomain logic.
  // This allows the submodules to use standard SpinalHDL registers.
  val coreClockDomain = ClockDomain(
    clock = io.clk24MHz,
    reset = io.reset,
    config = ClockDomainConfig(
      resetKind = ASYNC,
      resetActiveLevel = HIGH
    )
  )

  // System Integration Area
  val core = new ClockingArea(coreClockDomain) {

    // UART Subsystem Wrapper
    val uart              = new Uart()
    
    // Synthesis and Output Modules
    val timingGen         = new TimingGenerator()
    val oscillator        = new Oscillator()
    val envGen            = new EnvelopeGenerator()
    val envAttenuator     = new Attenuator(volumeWidth = 10)
    val attenuator        = new Attenuator()
    val svf               = new SVF()
    val decimator         = new Decimator()
    val transmitter       = new I2STransmitter()

    // --- UART Control Path ---
    uart.io.rx := io.uartRx

    // --- Synthesis Engine Wiring ---

    // 1. Tick & Control Distribution
    oscillator.io.phaseTick        := timingGen.io.phaseTick
    envGen.io.phaseTick            := timingGen.io.phaseTick
    envGen.io.syncIn               := False
    // svf.io.phaseTick               := timingGen.io.phaseTick

    val alignedSampleTick          = Delay(timingGen.io.sampleTick, cycleCount = 2)
    decimator.io.sampleTick        := alignedSampleTick
    svf.io.phaseTick               := alignedSampleTick

    // 2. Control Signals (UART Subsystem -> Synth Engine)
    oscillator.io.config           := uart.io.config
    envGen.io.config               := uart.io.envConfig
    svf.io.config                  := uart.io.filterConfig
    val envBypassed = !uart.io.envConfig.ctrl(0)
    envAttenuator.io.volume        := envBypassed ? U(1023, 10 bits) | envGen.io.envelopeOut.payload
    attenuator.io.volume           := uart.io.config.volume

    // 3. Audio Data Path
    // Oscillator (480kHz) -> Envelope Attenuator -> Master Volume Attenuator -> SVF Filter -> Decimator -> I2S Transmitter (48kHz)
    oscillator.io.sample           >> envAttenuator.io.sampleIn
    envAttenuator.io.sampleOut     >> attenuator.io.sampleIn
    attenuator.io.sampleOut        >> svf.io.sampleIn
    svf.io.sampleOut               >> decimator.io.sampleIn
    decimator.io.sampleOut         >> transmitter.io.sampleIn

    // --- External Output Mapping ---
    io.i2sBclk  := transmitter.io.bclk
    io.i2sLrclk := transmitter.io.lrclk
    io.i2sData  := transmitter.io.sdata
  }
}
