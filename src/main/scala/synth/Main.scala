package synth

import spinal.core._

object Main {
  def main(args: Array[String]): Unit = {
    // Configure the generator to output to the project's rtl/ folder
    val config = SpinalConfig(
      targetDirectory = "rtl",
      defaultConfigForClockDomains = ClockDomainConfig(resetActiveLevel = HIGH)
    )
    config.generateVerilog(new Synth(numVoices = 2))
  }
}