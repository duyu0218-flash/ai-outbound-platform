"""Bounded synthetic-load telemetry, preserving lifetime counts and maxima."""
from collections import Counter, deque
import logging
from logging.handlers import RotatingFileHandler
import math
import threading


class Samples:
    def __init__(self, limit=10000):
        self.values = deque(maxlen=limit)
        self.count = 0
        self.total = self.maximum = 0.
        self.bins = Counter()
        self.lock = threading.Lock()

    def append(self, value):
        value = float(value)
        if not math.isfinite(value) or value < 0:
            raise ValueError('finite nonnegative sample required')
        with self.lock:
            self.values.append(value)
            self.count += 1
            self.total += value
            self.maximum = max(self.maximum, value)
            # 1% conservative upper-bound bins after exact sample retention fills.
            self.bins[math.ceil(math.log1p(value)/math.log(1.01))] += 1

    def __len__(self):
        return self.count

    def __getitem__(self, index):
        return self.values[index]

    def quantile(self, fraction):
        if not 0 <= fraction <= 1:
            raise ValueError('quantile must be in [0, 1]')
        with self.lock:
            if not self.count:
                return 0.
            rank = min(self.count-1, int(self.count*fraction))
            if self.count <= self.values.maxlen:
                return sorted(self.values)[rank]
            accumulated = 0
            for index, count in sorted(self.bins.items()):
                accumulated += count
                if accumulated > rank:
                    return min(self.maximum, math.expm1(index*math.log(1.01)))


class StrictRotatingHandler(RotatingFileHandler):
    def handleError(self, record):
        raise OSError('load log capture failed')


class ProcessLog:
    """Continuously drain a subprocess into bounded files; count retries before rotation."""
    def __init__(self, path):
        self.handler = StrictRotatingHandler(path, maxBytes=8*1024*1024, backupCount=3, encoding='utf-8')
        self.retries = 0
        self.error = None

    def attach(self, pipe):
        def drain():
            try:
                for line in iter(pipe.readline, b''):
                    text = line.decode('utf-8', errors='replace').rstrip('\n')
                    self.retries += text.count('AI transport retry error_type=')
                    self.handler.emit(logging.LogRecord('load', logging.INFO, '', 0, text, (), None))
            except Exception as exc:
                self.error = type(exc).__name__
            finally:
                pipe.close()
        self.thread = threading.Thread(target=drain, daemon=True)
        self.thread.start()

    def flush(self):
        self.handler.flush()

    def close(self):
        self.thread.join(timeout=10)
        if self.thread.is_alive():
            self.error = 'log_reader_did_not_stop'
        self.handler.close()
