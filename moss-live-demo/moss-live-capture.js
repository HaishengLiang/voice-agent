// AudioWorklet: 麦克风采集 -> 低通滤波 -> 重采样到 16 kHz / 20 ms 帧
// 输出 PCM16LE 单声道，每帧 640 字节，通过 postMessage 发给主线程。

const OUTPUT_RATE = 16_000
const FRAME_SAMPLES = 320
const FRAME_BYTES = FRAME_SAMPLES * 2

function lowPassKernel(rate) {
  if (rate <= OUTPUT_RATE) return new Float64Array([1])

  const half = Math.ceil((rate / OUTPUT_RATE) * 16)
  const cutoff = (OUTPUT_RATE * 0.45) / rate
  const kernel = new Float64Array(half * 2 + 1)
  let sum = 0

  for (let index = 0; index < kernel.length; index += 1) {
    const offset = index - half
    const sinc =
      offset === 0
        ? 2 * cutoff
        : Math.sin(2 * Math.PI * cutoff * offset) / (Math.PI * offset)
    kernel[index] = sinc * (0.54 - 0.46 * Math.cos((2 * Math.PI * index) / (kernel.length - 1)))
    sum += kernel[index]
  }
  for (let index = 0; index < kernel.length; index += 1) kernel[index] /= sum
  return kernel
}

class MossLiveCapture extends AudioWorkletProcessor {
  constructor() {
    super()
    this.kernel = lowPassKernel(sampleRate)
    this.history = new Float32Array(this.kernel.length)
    this.historyIndex = 0
    this.previous = 0
    this.phase = 0
    this.frame = new DataView(new ArrayBuffer(FRAME_BYTES))
    this.frameIndex = 0
  }

  process(inputs, outputs) {
    const channels = inputs[0] ?? []
    const length = channels[0]?.length ?? outputs[0]?.[0]?.length ?? 128

    for (let index = 0; index < length; index += 1) {
      let input = 0
      for (const channel of channels) input += channel[index]
      if (channels.length) input /= channels.length

      this.history[this.historyIndex] = input
      let filtered = 0
      for (let tap = 0; tap < this.kernel.length; tap += 1) {
        const at = (this.historyIndex - tap + this.history.length) % this.history.length
        filtered += this.history[at] * this.kernel[tap]
      }
      this.historyIndex = (this.historyIndex + 1) % this.history.length

      this.phase += OUTPUT_RATE
      while (this.phase >= sampleRate) {
        const fraction = 1 - (this.phase - sampleRate) / OUTPUT_RATE
        const value = this.previous + (filtered - this.previous) * fraction
        const clipped = Math.max(-1, Math.min(1, value))

        this.frame.setInt16(
          this.frameIndex * 2,
          Math.round(clipped * (clipped < 0 ? 32_768 : 32_767)),
          true,
        )
        this.frameIndex += 1

        if (this.frameIndex === FRAME_SAMPLES) {
          this.port.postMessage(this.frame.buffer, [this.frame.buffer])
          this.frame = new DataView(new ArrayBuffer(FRAME_BYTES))
          this.frameIndex = 0
        }
        this.phase -= sampleRate
      }
      this.previous = filtered
    }

    // 保持输出静音，仅用于推进 AudioContext 的实时时钟。
    return true
  }
}

registerProcessor('moss-live-capture', MossLiveCapture)
