import os
import random

import numpy as np

try:
    import torch
    import torch.nn as nn
    from torch.utils.data import DataLoader, Dataset
    TORCH_AVAILABLE = True
except ImportError:
    torch = None
    nn = None
    DataLoader = None
    Dataset = object
    TORCH_AVAILABLE = False

_SEED = int(os.getenv("SEED", "42"))
random.seed(_SEED)
np.random.seed(_SEED)
if TORCH_AVAILABLE:
    torch.manual_seed(_SEED)
    torch.set_num_threads(int(os.getenv("TORCH_NUM_THREADS", "1")))

ENGINE_NAME = "signature_engine_v1"
WEARABLE_ENGINE_NAME = "wearable_signature_engine_v1"
LSTM_ENGINE_NAME = "lstm_ae_v1"

BASELINE_HOURS = int(os.getenv("BASELINE_HOURS", "24"))
LOOP_INTERVAL_SEC = int(os.getenv("LOOP_INTERVAL_SEC", "300"))
GPS_MAX_ACCURACY_M = float(os.getenv("GPS_MAX_ACCURACY_M", "50"))

SEQ_LEN = int(os.getenv("LSTM_SEQ_LEN", "24"))
TRAIN_LOOKBACK_HOURS = int(os.getenv("LSTM_TRAIN_LOOKBACK_HOURS", str(24 * 7)))
TRAIN_EPOCHS = int(os.getenv("LSTM_EPOCHS", "8"))
TRAIN_BATCH_SIZE = int(os.getenv("LSTM_BATCH_SIZE", "32"))
TRAIN_LR = float(os.getenv("LSTM_LR", "0.001"))
ANOMALY_PERCENTILE = float(os.getenv("LSTM_ANOMALY_PERCENTILE", "95"))
RETRAIN_EVERY_HOURS = int(os.getenv("LSTM_RETRAIN_EVERY_HOURS", str(24)))
LSTM_MIN_TRAIN_ROWS = int(os.getenv("LSTM_MIN_TRAIN_ROWS", str(max(SEQ_LEN + 12, 36))))
LSTM_MIN_TRAIN_SEQUENCES = int(os.getenv("LSTM_MIN_TRAIN_SEQUENCES", "6"))
MODEL_DIR = os.getenv("MODEL_DIR", os.path.join(os.path.dirname(os.path.dirname(__file__)), "models"))

WEARABLE_BASELINE_DAYS = int(os.getenv("WEARABLE_BASELINE_DAYS", "14"))
WEARABLE_Z_ALERT = float(os.getenv("WEARABLE_Z_ALERT", "2.0"))
PHONE_GPS_LOW_DISTANCE_M = float(os.getenv("PHONE_GPS_LOW_DISTANCE_M", "20.0"))
PHONE_GPS_STATIONARY_MIN = float(os.getenv("PHONE_GPS_STATIONARY_MIN", "0.9"))
PHONE_LOW_MOTION_RATIO_MAX = float(os.getenv("PHONE_LOW_MOTION_RATIO_MAX", "0.4"))
PHONE_DS2_SCREEN_ON_RATIO = float(os.getenv("PHONE_DS2_SCREEN_ON_RATIO", "2.0"))
PHONE_DS2_SCREEN_SESSION_RATIO = float(os.getenv("PHONE_DS2_SCREEN_SESSION_RATIO", "1.8"))
PHONE_ENTROPY_Z_ALERT = float(os.getenv("PHONE_ENTROPY_Z_ALERT", "2.0"))
PHONE_GPS_RATIO_ALERT = float(os.getenv("PHONE_GPS_RATIO_ALERT", "2.5"))
PHONE_NIGHT_SCREEN_RATIO_ALERT = float(os.getenv("PHONE_NIGHT_SCREEN_RATIO_ALERT", "3.0"))
PHONE_HIGH_MOTION_RATIO_ALERT = float(os.getenv("PHONE_HIGH_MOTION_RATIO_ALERT", "2.0"))
PHONE_SU3_SCREEN_SESSION_RATIO = float(os.getenv("PHONE_SU3_SCREEN_SESSION_RATIO", "2.5"))
PHONE_SU3_SCREEN_ON_RATIO = float(os.getenv("PHONE_SU3_SCREEN_ON_RATIO", "2.5"))
PHONE_PS1_RECENT_ALERT_MIN = int(os.getenv("PHONE_PS1_RECENT_ALERT_MIN", "12"))
PHONE_PS1_GROWTH_RATIO = float(os.getenv("PHONE_PS1_GROWTH_RATIO", "1.5"))
PHONE_PS1_HIGH_GROWTH_RATIO = float(os.getenv("PHONE_PS1_HIGH_GROWTH_RATIO", "2.0"))
PHONE_BP1_VARIABILITY_RATIO = float(os.getenv("PHONE_BP1_VARIABILITY_RATIO", "1.7"))
WEARABLE_VARIABILITY_RATIO = float(os.getenv("WEARABLE_VARIABILITY_RATIO", "1.7"))
