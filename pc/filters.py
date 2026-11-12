"""
phoneelSteeringFilter
=====================
Single-axis steering signal processor:
  1. Center deadzone normalization
  2. Non-linear exponential response curve
  3. Optional XInput anti-deadzone offset
  4. Speed-dependent & angular-velocity adaptive EMA low-pass filter
"""


class SteeringFilter:
    def __init__(
        self,
        alpha: float = 0.70,
        deadzone: float = 0.005,
        anti_deadzone: float = 0.00,
        steering_expo: float = 1.35,
        speed_damping: float = 0.45,
    ):
        self.alpha = alpha
        self.deadzone = deadzone
        self.anti_deadzone = anti_deadzone
        self.steering_expo = steering_expo
        self.speed_damping = speed_damping
        self._filtered = 0.0

    def reset(self):
        self._filtered = 0.0

    def process(self, value: float, speed_kmh: float = 0.0) -> float:
        val = self._apply_deadzone(value)

        if abs(val) > 0.0 and self.steering_expo != 1.0:
            sign = 1.0 if val > 0 else -1.0
            expo = max(1.0, min(2.2, self.steering_expo))
            val = sign * (abs(val) ** expo)

        val = self._apply_anti_deadzone(val)

        delta = abs(val - self._filtered)
        if delta > 0.10:
            # High angular velocity (counter-steering): bypass low-pass lag
            effective_alpha = max(self.alpha, min(0.96, self.alpha + (delta - 0.10) * 1.8))
        else:
            # High-speed straight-line stability damping near center
            speed_factor = max(0.0, min(1.0, (speed_kmh - 60.0) / 180.0))
            center_weight = max(0.0, 1.0 - abs(val) * 1.6)
            damping = speed_factor * center_weight * self.speed_damping * 0.45
            effective_alpha = max(0.22, self.alpha * (1.0 - damping))

        self._filtered = effective_alpha * val + (1.0 - effective_alpha) * self._filtered
        return max(-1.0, min(1.0, self._filtered))

    def _apply_deadzone(self, value: float) -> float:
        if abs(value) < self.deadzone:
            return 0.0
        sign = 1.0 if value > 0 else -1.0
        return sign * (abs(value) - self.deadzone) / (1.0 - self.deadzone)

    def _apply_anti_deadzone(self, value: float) -> float:
        if self.anti_deadzone <= 0.0005 or abs(value) < 0.0005:
            return value
        sign = 1.0 if value > 0 else -1.0
        return sign * (self.anti_deadzone + (1.0 - self.anti_deadzone) * abs(value))
