package com.silverithm.vehicleplacementsystem.controller;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.silverithm.vehicleplacementsystem.dto.AssignmentResponseDTO;
import com.silverithm.vehicleplacementsystem.dto.DispatchHistoryDTO;
import com.silverithm.vehicleplacementsystem.dto.DispatchHistoryDetailDTO;
import com.silverithm.vehicleplacementsystem.dto.Location;
import com.silverithm.vehicleplacementsystem.dto.RequestDispatchDTO;
import com.silverithm.vehicleplacementsystem.service.DispatchHistoryService;
import com.silverithm.vehicleplacementsystem.service.DispatchService;
import com.silverithm.vehicleplacementsystem.service.DispatchServiceV2;
import com.silverithm.vehicleplacementsystem.service.DispatchServiceV3;
import com.silverithm.vehicleplacementsystem.service.DispatchServiceV4;
import com.silverithm.vehicleplacementsystem.service.SSEService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class DispatchController {

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private DispatchServiceV2 dispatchServiceV2;

    @Autowired
    private DispatchServiceV3 dispatchServiceV3;

    @Autowired
    private DispatchServiceV4 dispatchServiceV4;

    @Autowired
    private DispatchServiceV4 dispatchServiceV5;

    @Autowired
    private SSEService sseService;

    @Autowired
    private DispatchHistoryService dispatchHistoryService;

    @PostMapping("/api/v1/dispatch")
    public ResponseEntity<List<AssignmentResponseDTO>> dispatch(@RequestBody RequestDispatchDTO requestDispatchDTO)
            throws Exception {

        try {
            return ResponseEntity.ok().body(dispatchServiceV3.getOptimizedAssignments(requestDispatchDTO));
        } catch (Exception e) {
            sseService.notifyError(requestDispatchDTO.userName());
            return ResponseEntity.badRequest().build();
        }

    }

    @GetMapping("/api/v1/history")
    public List<DispatchHistoryDTO> getHistories() {
        return dispatchHistoryService.getDispatchHistories();
    }

    @GetMapping("/api/v1/history/{id}")
    public DispatchHistoryDetailDTO getHistoryDetail(@PathVariable Long id) throws JsonProcessingException {
        return dispatchHistoryService.getDispatchDetail(id);
    }


}
