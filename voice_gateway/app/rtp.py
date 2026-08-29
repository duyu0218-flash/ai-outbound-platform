from __future__ import annotations

import struct
from dataclasses import dataclass


@dataclass(frozen=True)
class RtpPacket:
    payload_type: int
    sequence: int
    timestamp: int
    ssrc: int
    payload: bytes
    marker: bool = False

    def encode(self) -> bytes:
        if not 0 <= self.payload_type <= 127:
            raise ValueError("payload_type out of range")
        header = struct.pack(
            "!BBHII",
            0x80,
            self.payload_type | (0x80 if self.marker else 0),
            self.sequence & 0xFFFF,
            self.timestamp & 0xFFFFFFFF,
            self.ssrc & 0xFFFFFFFF,
        )
        return header + self.payload

    @classmethod
    def decode(cls, data: bytes) -> "RtpPacket":
        if len(data) < 12:
            raise ValueError("RTP packet is shorter than header")
        first, second, sequence, timestamp, ssrc = struct.unpack("!BBHII", data[:12])
        if first >> 6 != 2:
            raise ValueError("unsupported RTP version")
        csrc_count = first & 0x0F
        extension = bool(first & 0x10)
        offset = 12 + csrc_count * 4
        if len(data) < offset:
            raise ValueError("truncated RTP CSRC list")
        if extension:
            if len(data) < offset + 4:
                raise ValueError("truncated RTP extension")
            extension_words = struct.unpack("!H", data[offset + 2:offset + 4])[0]
            offset += 4 + extension_words * 4
        if len(data) < offset:
            raise ValueError("truncated RTP payload")
        return cls(second & 0x7F, sequence, timestamp, ssrc, data[offset:], bool(second & 0x80))


def _linear_to_ulaw(sample: int) -> int:
    bias = 0x84
    clip = 32635
    sign = 0x80 if sample < 0 else 0
    value = min(abs(sample), clip) + bias
    exponent = max(0, value.bit_length() - 8)
    mantissa = (value >> (exponent + 3)) & 0x0F
    return (~(sign | (exponent << 4) | mantissa)) & 0xFF


def _ulaw_to_linear(value: int) -> int:
    value = (~value) & 0xFF
    sample = ((value & 0x0F) << 3) + 0x84
    sample <<= (value >> 4) & 0x07
    sample -= 0x84
    return -sample if value & 0x80 else sample


def pcm16_to_pcmu(pcm: bytes) -> bytes:
    if len(pcm) % 2:
        raise ValueError("PCM16 input must contain complete samples")
    return bytes(_linear_to_ulaw(sample) for (sample,) in struct.iter_unpack("<h", pcm))


def pcmu_to_pcm16(data: bytes) -> bytes:
    return b"".join(struct.pack("<h", _ulaw_to_linear(value)) for value in data)


def _linear_to_alaw(sample: int) -> int:
    mask = 0xD5 if sample >= 0 else 0x55
    value = sample if sample >= 0 else -sample - 8
    value = min(value, 32635)
    if value < 256:
        encoded = value >> 4
    else:
        segment = min(7, value.bit_length() - 8)
        encoded = (segment << 4) | ((value >> (segment + 3)) & 0x0F)
    return encoded ^ mask


def _alaw_to_linear(value: int) -> int:
    value ^= 0x55
    sample = (value & 0x0F) << 4
    segment = (value & 0x70) >> 4
    if segment == 0:
        sample += 8
    elif segment == 1:
        sample += 0x108
    else:
        sample = (sample + 0x108) << (segment - 1)
    return sample if value & 0x80 else -sample


def pcm16_to_pcma(pcm: bytes) -> bytes:
    if len(pcm) % 2:
        raise ValueError("PCM16 input must contain complete samples")
    return bytes(_linear_to_alaw(sample) for (sample,) in struct.iter_unpack("<h", pcm))


def pcma_to_pcm16(data: bytes) -> bytes:
    return b"".join(struct.pack("<h", _alaw_to_linear(value)) for value in data)
