/// Proportional-Integral-Derivative Controller
/// Used for smooth drift correction (adjusting playback speed)
pub struct PidController {
    kp: f64,
    ki: f64,
    kd: f64,
    integral: f64,
    last_error: f64,
    max_integral: f64,
}

impl PidController {
    pub fn new(kp: f64, ki: f64, kd: f64) -> Self {
        Self {
            kp,
            ki,
            kd,
            integral: 0.0,
            last_error: 0.0,
            max_integral: 100.0, // Clamp integral windup
        }
    }

    pub fn reset(&mut self) {
        self.integral = 0.0;
        self.last_error = 0.0;
    }

    /// Calculate control output (playback speed adjustment)
    /// error: Target - Current (Drift) -> We want 0 drift.
    /// dt: Delta time in seconds
    pub fn next(&mut self, error: f64, dt: f64) -> f64 {
        self.integral += error * dt;
        self.integral = self.integral.clamp(-self.max_integral, self.max_integral);
        
        let derivative = if dt > 0.0 { (error - self.last_error) / dt } else { 0.0 };
        self.last_error = error;
        
        let output = (self.kp * error) + (self.ki * self.integral) + (self.kd * derivative);
        output
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_pid_convergence() {
        let mut pid = PidController::new(0.1, 0.01, 0.05);
        let mut current_drift = 50.0; // 50ms drift
        
        for _ in 0..100 {
            let correction = pid.next(-current_drift, 0.1); 
            // Simulate system response: correction reduces drift
            current_drift += correction * 0.5; 
        }
        
        assert!(current_drift.abs() < 10.0, "PID should converge drift towards 0");
    }
}
