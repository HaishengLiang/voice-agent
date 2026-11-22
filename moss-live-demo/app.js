const WS_BASE = 'wss://www.moss.cn/api/v1/public/website/moss-live/ws'
const GREETING_BASE = 'https://www.moss.cn/api/moss-live/greeting'
const PLAYBACK_RATE = 48_000

const $ = (id) => document.getElementById(id)
const ui = {
  voice: $('voice'),
  greeting: $('greeting'),
  toggle: $('toggle'),
  testTone: $('testTone'),
  testGreeting: $('testGreeting'),
  clear: $('clear'),
  status: $('status'),
  session: $('session'),
  counters: $('counters'),
  log: $('log'),
}

function log(tag, payload) {
  const time = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  const text = payload === undefined ? '' : typeof payload === 'string' ? payload : JSON.stringify(payload)
  ui.log.textContent += `[${time}] ${tag}${text ? ' ' + text : ''}\n`
  ui.log.scrollTop = ui.log.scrollHeight
}

function setStatus(status) {
  ui.status.textContent = status
  ui.status.dataset.status = status
}

function updateCounters(tx, rx) {
  ui.counters.textContent = `tx: ${tx} / rx: ${rx}`
}

class MossLiveDemo {
  constructor({ voice, playGreeting }) {
    this.voice = voice
    this.playGreeting = playGreeting
    this.ws = null
    this.ctx = null
    this.stream = null
    this.source = null
    this.capture = null
    this.mute = null
    this.attached = false
    this.micReady = false
    this.ended = false
    this.timers = []
    this.txFrames = 0
    this.rxFrames = 0
    this.contexts = []
    this.turns = new Map()
    this.greetingAudio = null
    updateCounters(0, 0)
  }

  async start() {
    setStatus('connecting')
    log('start', { voice: this.voice })

    // 保留用户点击时的自动播放权限
    this.greetingAudio = new Audio(`${GREETING_BASE}?voice=${this.voice}`)
    this.greetingAudio.preload = 'auto'

    this.ctx = new AudioContext({ latencyHint: 'interactive' })
    await this.ctx.resume()
    await this.ctx.audioWorklet.addModule('./moss-live-capture.js')

    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1,
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
      },
    })

    this.source = this.ctx.createMediaStreamSource(this.stream)
    this.capture = new AudioWorkletNode(this.ctx, 'moss-live-capture', {
      numberOfInputs: 1,
      numberOfOutputs: 1,
      outputChannelCount: [1],
      channelCount: 1,
      channelCountMode: 'explicit',
    })
    this.mute = this.ctx.createGain()
    this.mute.gain.value = 0

    this.source.connect(this.capture)
    this.capture.connect(this.mute)
    this.mute.connect(this.ctx.destination)

    this.capture.port.onmessage = (event) => {
      this.txFrames += 1
      if (this.txFrames === 1 || this.txFrames % 100 === 0) {
        log('mic', `已采集 ${this.txFrames} 帧（${this.micReady ? '正在上行' : '等待会话'}）`)
        updateCounters(this.txFrames, this.rxFrames)
      }
      if (this.micReady) this.sendBinary(event.data)
    }

    const url = new URL(WS_BASE)
    url.searchParams.set('voice', this.voice)
    this.ws = new WebSocket(url)
    this.ws.binaryType = 'arraybuffer'
    this.ws.onopen = () => log('ws', 'open')
    this.ws.onmessage = (event) => {
      try {
        this.receive(event.data)
      } catch (error) {
        log('error', `处理消息失败: ${error.message}`)
      }
    }
    this.ws.onerror = () => {
      if (!this.ended) this.stop('connection error')
    }
    this.ws.onclose = (event) => {
      if (!this.ended) this.stop(`ws closed: ${event.code}`)
    }

    this.timers.push(setTimeout(() => {
      if (!this.attached) this.stop('连接超时（15s）')
    }, 15_000))
  }

  receive(data) {
    if (this.ended) return

    if (data instanceof ArrayBuffer) {
      this.receiveAudio(data)
      return
    }
    if (typeof data !== 'string') return

    let message
    try {
      message = JSON.parse(data)
    } catch {
      log('warn', '非法 JSON 消息')
      return
    }

    const action = message.action
    const payload = message.data ?? {}
    log('recv', { action, data: payload })

    switch (action) {
      case 'session_attached':
        this.attach(payload.session_id)
        break

      case 'session.closing':
        this.stop('服务端关闭会话')
        break

      case 'error':
        this.stop(`服务端错误: ${JSON.stringify(payload)}`)
        break

      case 'response.audio.context':
        if (payload.chunk_id) {
          this.contexts.push({
            run_id: payload.run_id,
            turn_id: payload.turn_id,
            chunk_id: payload.chunk_id,
          })
        }
        break

      case 'response.audio.done': {
        const turn = this.getTurn(payload)
        if (turn) {
          turn.done = true
          this.flushTurn(turn)
        }
        break
      }

      case 'response.audio.stop': {
        const turn = this.getTurn(payload)
        if (turn && !turn.stopped) {
          turn.stopped = true
          const played = this.stopTurn(turn)
          this.sendJson({
            action: 'playback.stopped',
            run_id: turn.run_id,
            turn_id: turn.turn_id,
            played_audio_ms: played,
            ...(typeof payload.reason === 'string' ? { reason: payload.reason } : {}),
          })
        }
        break
      }

      case 'response.ended':
        log('turn ended', payload.status)
        break

      default:
        break
    }
  }

  attach(sessionId) {
    if (this.attached) return
    this.attached = true
    ui.session.textContent = `session: ${sessionId ?? '-'}`
    setStatus('connected')

    this.micReady = this.sendJson({ action: 'mic_state', enabled: true })

    if (this.playGreeting && this.greetingAudio) {
      this.greetingAudio.currentTime = 0
      this.greetingAudio.play()
        .then(() => log('audio', '问候语开始播放'))
        .catch((error) => log('warn', `问候语播放失败: ${error.message}`))
    }

    this.timers.push(setTimeout(() => this.stop('已达 200s 上限'), 200_000))
  }

  getTurn(context) {
    if (!context?.run_id || !context?.turn_id) return null
    const key = JSON.stringify([context.run_id, context.turn_id])
    let turn = this.turns.get(key)
    if (!turn) {
      turn = {
        run_id: context.run_id,
        turn_id: context.turn_id,
        chunks: [],
        contexts: [],
        done: false,
        stopped: false,
        flushed: false,
        source: null,
        startedAt: 0,
        duration: 0,
      }
      this.turns.set(key, turn)
    }
    return turn
  }

  // 只做一件事：把一段回复的所有 PCM 帧拼成一个 AudioBuffer 整段播放。
  // 不再逐帧创建 BufferSource，避免帧间缝隙和叠加杂音。
  receiveAudio(buffer) {
    this.rxFrames += 1
    if (this.rxFrames === 1 || this.rxFrames % 20 === 0) {
      log('audio', `收到 ${this.rxFrames} 帧，本帧 ${buffer.byteLength} 字节`)
      updateCounters(this.txFrames, this.rxFrames)
    }

    const context = this.contexts.shift()
    if (!context) {
      log('warn', '收到无上下文的音频帧，已丢弃')
      return
    }
    const turn = this.getTurn(context)
    if (!turn || turn.stopped || turn.flushed) return

    const pcm16 = new Int16Array(buffer)
    const samples = new Float32Array(pcm16.length)
    for (let i = 0; i < pcm16.length; i += 1) samples[i] = pcm16[i] / 32_768

    turn.chunks.push(samples)
    turn.contexts.push(context)
  }

  flushTurn(turn) {
    if (turn.flushed || turn.stopped || !turn.done) return
    if (!turn.chunks.length) {
      setTimeout(() => this.flushTurn(turn), 50)
      return
    }
    turn.flushed = true

    const total = turn.chunks.reduce((sum, chunk) => sum + chunk.length, 0)
    const pcm = new Float32Array(total)
    let offset = 0
    for (const chunk of turn.chunks) {
      pcm.set(chunk, offset)
      offset += chunk.length
    }

    const audioBuffer = this.ctx.createBuffer(1, total, PLAYBACK_RATE)
    audioBuffer.copyToChannel(pcm, 0)

    const node = this.ctx.createBufferSource()
    node.buffer = audioBuffer
    node.connect(this.ctx.destination)
    node.onended = () => {
      node.disconnect()
      if (turn.stopped) return
      for (const chunk of turn.contexts) {
        this.sendJson({
          action: 'playback.chunk.completed',
          run_id: chunk.run_id,
          turn_id: chunk.turn_id,
          chunk_id: chunk.chunk_id,
        })
      }
      this.sendJson({
        action: 'playback.completed',
        run_id: turn.run_id,
        turn_id: turn.turn_id,
      })
    }

    turn.source = node
    turn.duration = audioBuffer.duration
    turn.startedAt = this.ctx.currentTime
    node.start()
    log('audio', `整段播放 ${turn.chunks.length} 帧 / ${audioBuffer.duration.toFixed(2)}s`)
  }

  stopTurn(turn) {
    let played = 0
    if (turn.source) {
      const now = this.ctx?.currentTime ?? 0
      played = Math.round(Math.max(0, Math.min(turn.duration, now - turn.startedAt)) * 1000)
      try {
        turn.source.onended = null
        turn.source.stop()
        turn.source.disconnect()
      } catch {
        // 已结束的节点忽略
      }
      turn.source = null
    }
    turn.chunks = []
    turn.contexts = []
    return played
  }

  sendJson(payload) {
    return this.send(JSON.stringify(payload))
  }

  sendBinary(buffer) {
    this.send(buffer)
  }

  send(payload) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return false
    try {
      this.ws.send(payload)
      return true
    } catch (error) {
      log('warn', `发送失败: ${error.message}`)
      return false
    }
  }

  stop(reason) {
    if (this.ended) return
    this.ended = true
    for (const timer of this.timers) clearTimeout(timer)
    this.timers = []

    for (const turn of this.turns.values()) this.stopTurn(turn)
    this.turns.clear()
    this.contexts = []

    if (this.ws) {
      this.ws.onmessage = null
      this.ws.onerror = null
      this.ws.onclose = null
      if (this.ws.readyState < WebSocket.CLOSING) this.ws.close()
      this.ws = null
    }

    this.greetingAudio?.pause()
    this.greetingAudio = null

    if (this.capture) {
      this.capture.port.onmessage = null
      this.capture.disconnect()
      this.capture = null
    }
    this.source?.disconnect()
    this.mute?.disconnect()
    this.stream?.getTracks().forEach((track) => track.stop())
    if (this.ctx && this.ctx.state !== 'closed') this.ctx.close()
    this.ctx = null

    this.attached = false
    this.micReady = false
    setStatus('idle')
    ui.session.textContent = ''
    if (reason) log('stopped', reason)
    ui.toggle.textContent = '开始通话'
    ui.toggle.disabled = false
  }
}

let session = null

ui.toggle.addEventListener('click', async () => {
  if (session) {
    session.stop('手动结束')
    session = null
    return
  }

  ui.toggle.disabled = true
  const next = new MossLiveDemo({
    voice: ui.voice.value,
    playGreeting: ui.greeting.checked,
  })
  session = next

  try {
    await next.start()
    ui.toggle.textContent = '结束通话'
  } catch (error) {
    log('error', error.message)
    next.stop()
    session = null
  } finally {
    ui.toggle.disabled = false
  }
})

ui.testTone.addEventListener('click', async () => {
  try {
    const ctx = new AudioContext()
    await ctx.resume()
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.frequency.value = 440
    gain.gain.setValueAtTime(0.0001, ctx.currentTime)
    gain.gain.exponentialRampToValueAtTime(0.2, ctx.currentTime + 0.02)
    gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.25)
    osc.connect(gain)
    gain.connect(ctx.destination)
    osc.start()
    osc.stop(ctx.currentTime + 0.28)
    log('test', '440 Hz 提示音已触发')
    setTimeout(() => ctx.close(), 500)
  } catch (error) {
    log('error', `提示音失败: ${error.message}`)
  }
})

ui.testGreeting.addEventListener('click', async () => {
  const audio = new Audio(`${GREETING_BASE}?voice=${ui.voice.value}`)
  try {
    await audio.play()
    log('test', '问候语已触发')
  } catch (error) {
    log('error', `问候语失败: ${error.message}`)
  }
})

ui.clear.addEventListener('click', () => {
  ui.log.textContent = ''
})

window.addEventListener('pagehide', () => session?.stop())
