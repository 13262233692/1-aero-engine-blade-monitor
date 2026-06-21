package com.aero.blademonitor.controller;

import com.aero.blademonitor.fatigue.FatigueWarningService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/fatigue")
public class FatigueWarningController {

    private final FatigueWarningService fatigueWarningService;

    public FatigueWarningController(FatigueWarningService fatigueWarningService) {
        this.fatigueWarningService = fatigueWarningService;
    }

    @PostMapping("/acknowledge")
    public Map<String, Object> acknowledgeWarning() {
        fatigueWarningService.acknowledgeWarning();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Warning acknowledged");
        return result;
    }

    @PostMapping("/reset")
    public Map<String, Object> resetAll() {
        fatigueWarningService.resetAll();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "All fatigue data reset");
        return result;
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("criticalWarningActive", fatigueWarningService.isCriticalWarningActive());
        result.put("totalCyclesProcessed", fatigueWarningService.getTotalCyclesProcessed());
        return result;
    }
}
