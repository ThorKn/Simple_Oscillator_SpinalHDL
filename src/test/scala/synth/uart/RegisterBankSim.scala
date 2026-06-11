package synth.uart

import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class RegisterBankSim extends AnyFunSuite {
  test("RegisterBank parameters update and atomic frequency updates") {
    // Compile RegisterBank with 2 voices to verify parameterization and isolation
    SimConfig.withWave.compile(new RegisterBank(numVoices = 2)).doSim { dut =>
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
      dut.io.regWrite.payload.address #= 0x18 // Voice 0 OSC_WAVE_SEL
      dut.io.regWrite.payload.data #= 0xAA
      
      // Advance simulation time by 50 units (5 cycles) while reset is active
      sleep(50)
      
      // Global config defaults
      assert(dut.io.synthConfig.mixerCtrl.toInt == 0, "mixerCtrl must remain 0 during reset")

      // Voice 0 defaults
      assert(dut.io.voiceConfig(0).osc.freqWord.toLong == 0, "Frequency must remain 0 during reset")
      assert(dut.io.voiceConfig(0).osc.waveSelect.toInt == 0, "waveSelect must remain 0 during reset")
      assert(dut.io.voiceConfig(0).osc.pwmWidth.toInt == 0, "pwmWidth must remain 0 during reset")
      assert(dut.io.voiceConfig(0).osc.volume.toInt == 0, "Volume must remain 0 during reset")
      
      assert(dut.io.voiceConfig(0).env.ctrl.toInt == 0, "env.ctrl must remain 0 during reset")
      assert(dut.io.voiceConfig(0).env.attack.toInt == 0, "env.attack must remain 0 during reset")
      assert(dut.io.voiceConfig(0).env.decay.toInt == 0, "env.decay must remain 0 during reset")
      assert(dut.io.voiceConfig(0).env.sustain.toInt == 0, "env.sustain must remain 0 during reset")
      assert(dut.io.voiceConfig(0).env.release.toInt == 0, "env.release must remain 0 during reset")
      assert(dut.io.voiceConfig(0).env.gate.toInt == 0, "env.gate must remain 0 during reset")
      
      // Clear write signals and wait for reset deassertion and stabilization
      dut.io.regWrite.valid #= false
      dut.io.regWrite.payload.address #= 0
      dut.io.regWrite.payload.data #= 0
      dut.clockDomain.waitSampling(100)
      println("Reset Defaults verified successfully.")

      // 1.2.2 Verify Global Register Writes
      println("Verifying Global Register Writes:")
      writeReg(0x00, 0x5A) // Write MIXER_CTRL
      assert(dut.io.synthConfig.mixerCtrl.toInt == 0x5A, s"Expected mixerCtrl to be 0x5A, got 0x${dut.io.synthConfig.mixerCtrl.toInt.toHexString}")
      println("Global Register Writes verified successfully.")

      // 1.2.3 Verify Single-Byte Direct Updates (Voice 0)
      println("Verifying Single-Byte Direct Updates (Voice 0):")
      
      // Write Waveform Select (OSC_WAVE_SEL - 0x18) -> 3 (Triangle)
      writeReg(0x18, 3)
      assert(dut.io.voiceConfig(0).osc.waveSelect.toInt == 3, s"Expected waveSelect to be 3, got ${dut.io.voiceConfig(0).osc.waveSelect.toInt}")

      // Write PWM Width (OSC_PWM_WIDTH - 0x19) -> 0xA5
      writeReg(0x19, 0xA5)
      assert(dut.io.voiceConfig(0).osc.pwmWidth.toInt == 0xA5, s"Expected pwmWidth to be 0xA5, got ${dut.io.voiceConfig(0).osc.pwmWidth.toInt}")

      // Write Volume (OSC_VOLUME - 0x1A) -> 0x7F
      writeReg(0x1A, 0x7F)
      assert(dut.io.voiceConfig(0).osc.volume.toInt == 0x7F, s"Expected volume to be 0x7F, got ${dut.io.voiceConfig(0).osc.volume.toInt}")

      println("Single-Byte Direct Updates verified successfully.")

      // 1.2.4 Verify Atomic 24-Bit Frequency Commitment (Voice 0)
      println("Verifying Atomic Frequency Updates:")

      // Step 1: Write Low byte (OSC_FREQ_LOW - 0x15) -> 0x55
      writeReg(0x15, 0x55)
      assert(dut.io.voiceConfig(0).osc.freqWord.toLong == 0, s"Expected frequency to remain 0 after OSC_FREQ_LOW write, got ${dut.io.voiceConfig(0).osc.freqWord.toLong}")

      // Step 2: Write Mid byte (OSC_FREQ_MID - 0x16) -> 0xAA
      writeReg(0x16, 0xAA)
      assert(dut.io.voiceConfig(0).osc.freqWord.toLong == 0, s"Expected frequency to remain 0 after OSC_FREQ_MID write, got ${dut.io.voiceConfig(0).osc.freqWord.toLong}")

      // Step 3: Write High byte (OSC_FREQ_HIGH - 0x17) -> 0x0C (Trigger Commit)
      writeReg(0x17, 0x0C)
      val expectedFreq = 0x0CAA55
      assert(dut.io.voiceConfig(0).osc.freqWord.toLong == expectedFreq, s"Expected atomic update to $expectedFreq, got ${dut.io.voiceConfig(0).osc.freqWord.toLong}")

      println("Atomic Frequency Updates verified successfully.")

      // 1.2.5 Verify Envelope Parameter Updates (Voice 0)
      println("Verifying Envelope Parameter Updates:")
      writeReg(0x1D, 0x15) // ENV_CTRL
      assert(dut.io.voiceConfig(0).env.ctrl.toInt == 0x15, s"Expected env.ctrl to be 0x15, got 0x${dut.io.voiceConfig(0).env.ctrl.toInt.toHexString}")

      writeReg(0x1E, 0x0A) // ENV_ATTACK
      assert(dut.io.voiceConfig(0).env.attack.toInt == 0x0A, s"Expected env.attack to be 0x0A, got ${dut.io.voiceConfig(0).env.attack.toInt}")

      writeReg(0x1F, 0x1F) // ENV_DECAY
      assert(dut.io.voiceConfig(0).env.decay.toInt == 0x1F, s"Expected env.decay to be 0x1F, got ${dut.io.voiceConfig(0).env.decay.toInt}")

      writeReg(0x20, 0x80) // ENV_SUSTAIN
      assert(dut.io.voiceConfig(0).env.sustain.toInt == 0x80, s"Expected env.sustain to be 0x80, got ${dut.io.voiceConfig(0).env.sustain.toInt}")

      writeReg(0x21, 0x2C) // ENV_RELEASE
      assert(dut.io.voiceConfig(0).env.release.toInt == 0x2C, s"Expected env.release to be 0x2C, got ${dut.io.voiceConfig(0).env.release.toInt}")

      writeReg(0x22, 0x05) // ENV_GATE
      assert(dut.io.voiceConfig(0).env.gate.toInt == 0x05, s"Expected env.gate to be 0x05, got 0x${dut.io.voiceConfig(0).env.gate.toInt.toHexString}")

      println("Envelope Parameter Updates verified successfully.")

      // 1.2.6 Verify Multi-Voice Address Isolation (Voice 1)
      println("Verifying Multi-Voice Isolation (Voice 1 at base 0x30):")
      
      // Write to Voice 1 registers
      writeReg(0x38, 4)    // Voice 1 Waveform Select (OSC_WAVE_SEL - 0x38)
      writeReg(0x3E, 0xBB) // Voice 1 Envelope Attack (ENV_ATTACK - 0x3E)
      
      // Assert Voice 1 updated correctly
      assert(dut.io.voiceConfig(1).osc.waveSelect.toInt == 4, s"Expected Voice 1 waveSelect to be 4, got ${dut.io.voiceConfig(1).osc.waveSelect.toInt}")
      assert(dut.io.voiceConfig(1).env.attack.toInt == 0xBB, s"Expected Voice 1 env.attack to be 0xBB, got ${dut.io.voiceConfig(1).env.attack.toInt}")
      
      // Assert Voice 0 remains completely untouched by Voice 1 writes
      assert(dut.io.voiceConfig(0).osc.waveSelect.toInt == 3, "Voice 0 waveSelect was corrupted by Voice 1 write")
      assert(dut.io.voiceConfig(0).env.attack.toInt == 0x0A, "Voice 0 env.attack was corrupted by Voice 1 write")
      
      println("Multi-Voice Isolation verified successfully.")
    }
  }
}
