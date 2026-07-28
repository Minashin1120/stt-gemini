class SttPcmCaptureProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this.captureBuffer = new Float32Array(4096);
        this.captureOffset = 0;
        this.paused = false;

        this.port.onmessage = (event) => {
            const message = event.data || {};
            if (message.type === 'pause') {
                this.paused = !!message.value;
            } else if (message.type === 'flush') {
                this.flush();
                this.port.postMessage({ type: 'flushed' });
            }
        };
    }

    flush() {
        if (this.captureOffset === 0) return;
        const chunk = this.captureBuffer.slice(0, this.captureOffset);
        this.port.postMessage({ type: 'pcm', buffer: chunk.buffer }, [chunk.buffer]);
        this.captureOffset = 0;
    }

    capture(input) {
        let sourceOffset = 0;
        while (sourceOffset < input.length) {
            const count = Math.min(
                input.length - sourceOffset,
                this.captureBuffer.length - this.captureOffset,
            );
            this.captureBuffer.set(
                input.subarray(sourceOffset, sourceOffset + count),
                this.captureOffset,
            );
            this.captureOffset += count;
            sourceOffset += count;

            if (this.captureOffset === this.captureBuffer.length) {
                const chunk = this.captureBuffer;
                this.port.postMessage({ type: 'pcm', buffer: chunk.buffer }, [chunk.buffer]);
                this.captureBuffer = new Float32Array(4096);
                this.captureOffset = 0;
            }
        }
    }

    process(inputs, outputs) {
        const input = inputs[0] && inputs[0][0];
        const output = outputs[0] && outputs[0][0];

        if (input) {
            if (!this.paused) this.capture(input);
            if (output) output.set(input);
        } else if (output) {
            output.fill(0);
        }
        return true;
    }
}

registerProcessor('stt-pcm-capture', SttPcmCaptureProcessor);
