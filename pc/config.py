"""
phoneel Configuration
"""

import json
import os
from dataclasses import dataclass, asdict

CONFIG_FILE = "phoneel_config.json"


@dataclass
class PhoneelConfig:
    port: int = 9999
    discovery_port: int = 9998

    filter_alpha: float = 0.65
    deadzone: float = 0.005
    anti_deadzone: float = 0.00
    steering_expo: float = 1.30
    speed_damping: float = 0.50

    show_dashboard: bool = True

    def save(self, path: str = CONFIG_FILE):
        with open(path, 'w') as f:
            json.dump(asdict(self), f, indent=2)

    @classmethod
    def load(cls, path: str = CONFIG_FILE) -> 'PhoneelConfig':
        if not os.path.exists(path):
            config = cls()
            config.save(path)
            return config
        try:
            with open(path, 'r') as f:
                data = json.load(f)
            return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})
        except Exception as e:
            print(f"[phoneel] Config error: {e}, using defaults")
            return cls()
