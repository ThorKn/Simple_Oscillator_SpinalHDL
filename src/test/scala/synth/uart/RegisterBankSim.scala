package synth.uart

import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class RegisterBankSim extends AnyFunSuite {
  test("RegisterBank parameters update and atomic frequency updates") {
    SimConfig.withWave.compile(new RegisterBank).doSim { dut =>
      // 1. Fork the clock and reset using standard forkStimulus
      dut.clockDomain.forkStimulus(period = 10)

      // Helper to perform a clean register write transaction
      def writeReg(address: Int, data: Int): Unit = {
        dut.io.regWrite.valid #= true
        dut.io.regWrite.payload.address #= address
        dut.io.regWrite.payload.data #= data
        dut.clockDomain.waitSampling() // Cycle 1: Latch input into storage register
        dut.io.regWrite.valid #= false
        dut.clockDomain.waitSampling() // Cycle 2: Propagate to output synchronization register
        dut.clockDomain.waitSampling() // Cycle 3: Stable configuration visible on outputs
      }

      // 1.2.1 Verify Reset Defaults during active reset
      println("Verifying Reset Defaults:")
      dut.io.regWrite.valid #= true
      dut.io.regWrite.payload.address #= 0x33
      dut.io.regWrite.payload.data #= 0xAA
      
      // Advance simulation time by 50 units (5 cycles) while reset is active
      sleep(50)
      
      assert(dut.io.voiceConfig.osc.freqWord.toLong == 0, "Frequency must remain 0 during reset")
      assert(dut.io.voiceConfig.osc.waveSelect.toInt == 0, "waveSelect must remain 0 during reset")
      assert(dut.io.voiceConfig.osc.pwmWidth.toInt == 0, "pwmWidth must remain 0 during reset")
      assert(dut.io.voiceConfig.osc.volume.toInt == 0, "Volume must remain 0 during reset")
      
      // Assert Envelope config fields remain strictly 0 under active reset
      assert(dut.io.voiceConfig.env.ctrl.toInt == 0, "envConfig.ctrl must remain 0 during reset")
      assert(dut.io.voiceConfig.env.attack.toInt == 0, "envConfig.attack must remain 0 during reset")
      assert(dut.io.voiceConfig.env.decay.toInt == 0, "envConfig.decay must remain 0 during reset")
      assert(dut.io.voiceConfig.env.sustain.toInt == 0, "envConfig.sustain must remain 0 during reset")
      assert(dut.io.voiceConfig.env.release.toInt == 0, "envConfig.release must remain 0 during reset")
      assert(dut.io.voiceConfig.env.gate.toInt == 0, "envConfig.gate must remain 0 during reset")
      
      // Clear write signals and wait for reset deassertion and stabilization
      dut.io.regWrite.valid #= false
      dut.io.regWrite.payload.address #= 0
      dut.io.regWrite.payload.data #= 0
      dut.clockDomain.waitSampling(100)
      println("Reset Defaults verified successfully.")

      // 1.2.2 Verify Single-Byte Direct Updates
      println("Verifying Single-Byte Direct Updates:")
      
      // Write Waveform Select (OSC_WAVE_SEL - 0x33) -> 3 (Triangle)
      writeReg(0x33, 3)
      assert(dut.io.voiceConfig.osc.waveSelect.toInt == 3, s"Expected waveSelect to be 3, got ${dut.io.voiceConfig.osc.waveSelect.toInt}")

      // Write PWM Width (OSC_PWM_WIDTH - 0x34) -> 0xA5
      writeReg(0x34, 0xA5)
      assert(dut.io.voiceConfig.osc.pwmWidth.toInt == 0xA5, s"Expected pwmWidth to be 0xA5, got ${dut.io.voiceConfig.osc.pwmWidth.toInt}")

      // Write Volume (OSC_VOLUME - 0x35) -> 0x7F
      writeReg(0x35, 0x7F)
      assert(dut.io.voiceConfig.osc.volume.toInt == 0x7F, s"Expected volume to be 0x7F, got ${dut.io.voiceConfig.osc.volume.toInt}")

      println("Single-Byte Direct Updates verified successfully.")

      // 1.2.3 Verify Atomic 24-Bit Frequency Commitment
      println("Verifying Atomic Frequency Updates:")

      // Step 1: Write Low byte (OSC_FREQ_LOW) -> 0x30
      writeReg(0x30, 0x55)
      // Active frequency must remain unchanged (0)
      assert(dut.io.voiceConfig.osc.freqWord.toLong == 0, s"Expected frequency to remain 0 after OSC_FREQ_LOW write, got ${dut.io.voiceConfig.osc.freqWord.toLong}")

      // Step 2: Write Mid byte (OSC_FREQ_MID) -> 0x31
      writeReg(0x31, 0xAA)
      // Active frequency must still remain unchanged (0)
      assert(dut.io.voiceConfig.osc.freqWord.toLong == 0, s"Expected frequency to remain 0 after OSC_FREQ_MID write, got ${dut.io.voiceConfig.osc.freqWord.toLong}")

      // Step 3: Write High byte (OSC_FREQ_HIGH) -> 0x32 (Trigger Commit)
      writeReg(0x32, 0x0C)
      // In the next cycle, the 24-bit frequency word must atomically update to 0x0CAA55 (830037)
      val expectedFreq = 0x0CAA55
      assert(dut.io.voiceConfig.osc.freqWord.toLong == expectedFreq, s"Expected atomic update to $expectedFreq, got ${dut.io.voiceConfig.osc.freqWord.toLong}")

      println("Atomic Frequency Updates verified successfully.")

      // 1.2.4 Verify Envelope Parameter Updates
      println("Verifying Envelope Parameter Updates:")
      writeReg(0x40, 0x15)
      assert(dut.io.voiceConfig.env.ctrl.toInt == 0x15, s"Expected envConfig.ctrl to be 0x15, got 0x${dut.io.voiceConfig.env.ctrl.toInt.toHexString}")

      writeReg(0x41, 0x0A)
      assert(dut.io.voiceConfig.env.attack.toInt == 0x0A, s"Expected envConfig.attack to be 0x0A, got ${dut.io.voiceConfig.env.attack.toInt}")

      writeReg(0x42, 0x1F)
      assert(dut.io.voiceConfig.env.decay.toInt == 0x1F, s"Expected envConfig.decay to be 0x1F, got ${dut.io.voiceConfig.env.decay.toInt}")

      writeReg(0x43, 0x80)
      assert(dut.io.voiceConfig.env.sustain.toInt == 0x80, s"Expected envConfig.sustain to be 0x80, got ${dut.io.voiceConfig.env.sustain.toInt}")

      writeReg(0x44, 0x2C)
      assert(dut.io.voiceConfig.env.release.toInt == 0x2C, s"Expected envConfig.release to be 0x2C, got ${dut.io.voiceConfig.env.release.toInt}")

      writeReg(0x45, 0x05)
      assert(dut.io.voiceConfig.env.gate.toInt == 0x05, s"Expected envConfig.gate to be 0x05, got 0x${dut.io.voiceConfig.env.gate.toInt.toHexString}")

      println("Envelope Parameter Updates verified successfully.")

      // 1.2.5 Verify Address Crosstalk & Channel Isolation
      println("Verifying Address Crosstalk & Channel Isolation:")
      
      // Step A: Capture current envelope values
      val oldCtrl        = dut.io.voiceConfig.env.ctrl.toInt
      val oldAttack      = dut.io.voiceConfig.env.attack.toInt
      val oldDecay       = dut.io.voiceConfig.env.decay.toInt
      val oldSustain     = dut.io.voiceConfig.env.sustain.toInt
      val oldRelease     = dut.io.voiceConfig.env.release.toInt
      val oldGate        = dut.io.voiceConfig.env.gate.toInt

      // Write to oscillator registers
      writeReg(0x33, 1) // Change waveSelect to 1
      writeReg(0x34, 0x50)
      writeReg(0x35, 0x30)

      // Verify envelope values are completely unaffected
      assert(dut.io.voiceConfig.env.ctrl.toInt == oldCtrl, "Envelope ctrl must be isolated from osc writes")
      assert(dut.io.voiceConfig.env.attack.toInt == oldAttack, "Envelope attack must be isolated from osc writes")
      assert(dut.io.voiceConfig.env.decay.toInt == oldDecay, "Envelope decay must be isolated from osc writes")
      assert(dut.io.voiceConfig.env.sustain.toInt == oldSustain, "Envelope sustain must be isolated from osc writes")
      assert(dut.io.voiceConfig.env.release.toInt == oldRelease, "Envelope release must be isolated from osc writes")
      assert(dut.io.voiceConfig.env.gate.toInt == oldGate, "Envelope gate must be isolated from osc writes")

      // Step B: Capture current oscillator values
      val oldFreq = dut.io.voiceConfig.osc.freqWord.toLong
      val oldWave = dut.io.voiceConfig.osc.waveSelect.toInt
      val oldPwm  = dut.io.voiceConfig.osc.pwmWidth.toInt
      val oldVol  = dut.io.voiceConfig.osc.volume.toInt

      // Write to envelope registers
      writeReg(0x40, 0x00)
      writeReg(0x41, 0x00)
      writeReg(0x42, 0x00)
      
      // Verify oscillator values are completely unaffected
      assert(dut.io.voiceConfig.osc.freqWord.toLong == oldFreq, "Oscillator frequency must be isolated from env writes")
      assert(dut.io.voiceConfig.osc.waveSelect.toInt == oldWave, "Oscillator waveSelect must be isolated from env writes")
      assert(dut.io.voiceConfig.osc.pwmWidth.toInt == oldPwm, "Oscillator pwmWidth must be isolated from env writes")
      assert(dut.io.voiceConfig.osc.volume.toInt == oldVol, "Oscillator volume must be isolated from env writes")

      println("Address Crosstalk & Channel Isolation verified successfully.")
    }
  }
}
