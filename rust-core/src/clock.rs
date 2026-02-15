#[derive(Debug, Clone, Copy)]
pub struct ClockOffset {
    pub offset: i64,
    pub rtt: u64,
}

impl ClockOffset {
    /// Calculate offset and RTT from NTP timestamps
    /// t0: Client send time
    /// t1: Server receive time
    /// t2: Server transmit time
    /// t3: Client receive time
    pub fn calculate(t0: u64, t1: u64, t2: u64, t3: u64) -> Self {
        let rtt = (t3 - t0) - (t2 - t1);
        let offset = ((t1 as i64 - t0 as i64) + (t2 as i64 - t3 as i64)) / 2;
        
        Self {
            offset,
            rtt,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_offset_calculation() {
        let t0 = 1000;
        let t1 = 1100; // Latency 100ms, Server time = Client time + 0
        let t2 = 1200; // Processing 100ms
        let t3 = 1300; // Latency 100ms
        
        let result = ClockOffset::calculate(t0, t1, t2, t3);
        assert_eq!(result.rtt, 200);
        assert_eq!(result.offset, 0);
    }
    
    #[test]
    fn test_offset_with_drift() {
        // Server is ahead by 500ms
        let offset_real = 500;
        let latency = 50;
        
        let t0 = 1000;
        let t1 = 1000 + offset_real + latency; // 1550
        let t2 = t1 + 10; // Processing 10ms
        let t3 = t0 + latency + 10 + latency; // 1110 (1000 + 50 + 10 + 50)
        
        // t0=1000, t1=1550, t2=1560, t3=1110
        let result = ClockOffset::calculate(t0, t1, t2, t3);
        
        // RTT = (1110 - 1000) - (1560 - 1550) = 110 - 10 = 100. Correct (50+50).
        // Offset = ((1550 - 1000) + (1560 - 1110)) / 2 
        //        = (550 + 450) / 2 = 1000 / 2 = 500. Correct.
        
        assert_eq!(result.rtt, 100);
        assert_eq!(result.offset, 500);
    }
}
