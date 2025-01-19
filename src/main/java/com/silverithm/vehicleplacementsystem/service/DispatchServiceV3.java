package com.silverithm.vehicleplacementsystem.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.silverithm.vehicleplacementsystem.dto.AssignmentElderRequest;
import com.silverithm.vehicleplacementsystem.dto.AssignmentResponseDTO;
import com.silverithm.vehicleplacementsystem.dto.CompanyDTO;
import com.silverithm.vehicleplacementsystem.dto.CoupleRequestDTO;
import com.silverithm.vehicleplacementsystem.dto.ElderlyDTO;
import com.silverithm.vehicleplacementsystem.dto.EmployeeDTO;
import com.silverithm.vehicleplacementsystem.dto.FixedAssignmentsDTO;
import com.silverithm.vehicleplacementsystem.dto.KakaoMapApiResponseDTO;
import com.silverithm.vehicleplacementsystem.dto.Location;
import com.silverithm.vehicleplacementsystem.dto.OsrmApiResponseDTO;
import com.silverithm.vehicleplacementsystem.dto.RequestDispatchDTO;
import com.silverithm.vehicleplacementsystem.entity.ChromosomeV3;
import com.silverithm.vehicleplacementsystem.entity.DispatchType;
import com.silverithm.vehicleplacementsystem.entity.LinkDistance;
import com.silverithm.vehicleplacementsystem.repository.LinkDistanceRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
public class DispatchServiceV3 {


    private final LinkDistanceRepository linkDistanceRepository;
    private final SSEService sseService;
    private final DispatchHistoryService dispatchHistoryService;
    private final OsrmService osrmService;

    private String key;
    private String kakaoKey;


    public DispatchServiceV3(@Value("${tmap.key}") String key, @Value("${kakao.key}") String kakaoKey,
                             LinkDistanceRepository linkDistanceRepository,
                             SSEService sseService, DispatchHistoryService dispatchHistoryService,
                             OsrmService osrmService
    ) {
        this.linkDistanceRepository = linkDistanceRepository;
        this.sseService = sseService;
        this.key = key;
        this.kakaoKey = kakaoKey;
        this.dispatchHistoryService = dispatchHistoryService;
        this.osrmService = osrmService;
    }

    public KakaoMapApiResponseDTO getDistanceTotalTimeWithTmapApi(Location startAddress,
                                                                  Location destAddress) throws NullPointerException {

        String distanceString = "0";
        String durationString = "0";
        try {
            RestTemplate restTemplate = new RestTemplate();

            // HTTP 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "KakaoAK " + kakaoKey);

            // HTTP 엔터티 생성 (헤더 포함)
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // 파라미터 설정
            String origin = startAddress.getLongitude() + "," + startAddress.getLatitude();
            String destination = destAddress.getLongitude() + "," + destAddress.getLatitude();
            String waypoints = "";
            String priority = "DISTANCE";
            String carFuel = "GASOLINE";
            boolean carHipass = false;
            boolean alternatives = false;
            boolean roadDetails = false;

            // URL에 파라미터 추가
            String url = "https://apis-navi.kakaomobility.com/v1/directions" + "?origin=" + origin + "&destination="
                    + destination
                    + "&waypoints=" + waypoints + "&priority=" + priority + "&car_fuel=" + carFuel
                    + "&car_hipass=" + carHipass + "&alternatives=" + alternatives + "&road_details=" + roadDetails;

            // GET 요청 보내기
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            // result_code가 104이면 0 반환
            if (response.getBody().contains("\"result_code\":104")) {
                return new KakaoMapApiResponseDTO(0, 0); // 출발지와 도착지가 너무 가까운 경우 0 반환
            }

            distanceString = response.getBody().split("\"duration\":")[1].split("}")[0].trim();
            durationString = response.getBody().split("\"distance\":")[1].split(",")[0].trim();

        } catch (NullPointerException e) {
            throw new NullPointerException("[ERROR] KAKAOMAP API 요청에 실패하였습니다.");
        }

        log.info("Tmap API distance :  " + distanceString);

        return new KakaoMapApiResponseDTO(Integer.parseInt(durationString),
                Integer.parseInt(distanceString)); // 문자열을 정수형으로 변환

    }


    public List<AssignmentResponseDTO> getOptimizedAssignments(RequestDispatchDTO requestDispatchDTO, String jobId)
            throws Exception {

        List<EmployeeDTO> employees = requestDispatchDTO.employees();
        List<ElderlyDTO> elderlys = requestDispatchDTO.elderlys();
        List<CoupleRequestDTO> couples = requestDispatchDTO.couples();
        CompanyDTO company = requestDispatchDTO.company();
        List<FixedAssignmentsDTO> fixedAssignments = requestDispatchDTO.fixedAssignments();

        sseService.notify(jobId, 5);

        // 거리 행렬 계산
        Map<String, Map<String, Integer>> distanceMatrix = calculateDistanceMatrix(employees, elderlys, company,
                requestDispatchDTO.dispatchType(), jobId);
        sseService.notify(jobId, 15);

        // 유전 알고리즘 실행
        GeneticAlgorithmV3 geneticAlgorithm = new GeneticAlgorithmV3(employees, elderlys,
                couples,
                fixedAssignments,
                sseService);
        geneticAlgorithm.initialize(distanceMatrix, requestDispatchDTO.dispatchType(), requestDispatchDTO.userName());

        List<ChromosomeV3> chromosomes = geneticAlgorithm.run(jobId);
        // 최적의 솔루션 추출
        ChromosomeV3 bestChromosome = chromosomes.get(0);

        List<Double> departureTimes = bestChromosome.getDepartureTimes();
        sseService.notify(jobId, 95);

        List<AssignmentResponseDTO> assignmentResponseDTOS = createResult(
                employees, elderlys, bestChromosome, departureTimes, requestDispatchDTO.dispatchType());

        dispatchHistoryService.saveDispatchResult(assignmentResponseDTOS);

        log.info("done : " + bestChromosome.getGenes().toString() + " " + bestChromosome.getFitness() + " "
                + bestChromosome.getDepartureTimes());

        log.info(assignmentResponseDTOS.toString());

        sseService.notify(jobId, 100);
        sseService.notifyResult(jobId, assignmentResponseDTOS);

        return assignmentResponseDTOS;
    }

    private int[][] performDriverClustering(
            List<EmployeeDTO> employees,
            List<ElderlyDTO> elderlys,
            Map<String, Map<String, Integer>> distanceMatrix,
            CompanyDTO company
    ) {
        List<EmployeeDTO> drivers = employees.stream()
                .filter(EmployeeDTO::isDriver)
                .collect(Collectors.toList());

        if (drivers.isEmpty()) {
            return null;
        }

        int TIME_LIMIT = 3000; // 1시간
        Map<EmployeeDTO, Integer> driverTotalTimes = new HashMap<>();
        Map<EmployeeDTO, Integer> driverAssignedCounts = new HashMap<>();
        drivers.forEach(driver -> {
            driverTotalTimes.put(driver, 0);
            driverAssignedCounts.put(driver, 0);
        });

        Set<Integer> assignedElderlyIndices = new HashSet<>();
        List<List<Integer>> allRoutes = new ArrayList<>();

        // 전체 어르신 수를 기반으로 운전원당 목표 인원 계산
        int targetElderlyPerDriver = (int) Math.ceil((double) elderlys.size() / drivers.size());
        log.info("Target elderly per driver: {}", targetElderlyPerDriver);

        boolean canAssignMore = true;
        while (canAssignMore && assignedElderlyIndices.size() < elderlys.size()) {
            canAssignMore = false;

            // 가장 적은 수의 어르신이 배정된 운전원 선택
            EmployeeDTO currentDriver = drivers.stream()
                    .filter(d -> driverTotalTimes.get(d) < TIME_LIMIT)
                    .min(Comparator.comparingInt(driverAssignedCounts::get))
                    .orElse(null);

            if (currentDriver == null) break;

            int totalDriverTime = driverTotalTimes.get(currentDriver);
            int currentAssignedCount = driverAssignedCounts.get(currentDriver);

            // 이 운전원이 목표 인원에 도달했는지 확인
            if (currentAssignedCount >= targetElderlyPerDriver) {
                continue;
            }

            log.info("Processing driver {} (current count: {}, total time: {}s)",
                    currentDriver.id(), currentAssignedCount, totalDriverTime);

            // 아직 배정되지 않은 어르신들 수집
            List<Integer> unassignedElderlys = new ArrayList<>();
            for (int j = 0; j < elderlys.size(); j++) {
                if (!assignedElderlyIndices.contains(j)) {
                    unassignedElderlys.add(j);
                }
            }

            if (!unassignedElderlys.isEmpty()) {
                List<Integer> currentTrip = new ArrayList<>();
                String currentLocation = "Company";
                int routeTime = 0;
                int tripCapacity = Math.min(
                        currentDriver.maximumCapacity(),
                        targetElderlyPerDriver - currentAssignedCount
                );

                // 가장 가까운 어르신부터 배정
                while (!unassignedElderlys.isEmpty() && currentTrip.size() < tripCapacity) {
                    int nearestIndex = findNearestElderly(
                            currentLocation,
                            unassignedElderlys,
                            distanceMatrix,
                            elderlys
                    );

                    if (nearestIndex != -1) {
                        String elderlyId = "Elderly_" + elderlys.get(nearestIndex).id();
                        int timeToElderly = distanceMatrix.get(currentLocation).get(elderlyId);
                        int timeToCompany = distanceMatrix.get(elderlyId).get("Company");

                        int potentialTotalTime = totalDriverTime + routeTime + timeToElderly + timeToCompany;

                        if (potentialTotalTime <= TIME_LIMIT) {
                            currentTrip.add(nearestIndex);
                            routeTime += timeToElderly;
                            currentLocation = elderlyId;
                            unassignedElderlys.remove(Integer.valueOf(nearestIndex));
                        } else {
                            break;
                        }
                    } else {
                        break;
                    }
                }

                if (!currentTrip.isEmpty()) {
                    // 회사로 돌아오는 시간 추가
                    routeTime += distanceMatrix.get(currentLocation).get("Company");

                    // 배정 정보 업데이트
                    currentTrip.forEach(assignedElderlyIndices::add);
                    driverTotalTimes.put(currentDriver, totalDriverTime + routeTime);
                    driverAssignedCounts.merge(currentDriver, currentTrip.size(), Integer::sum);

                    allRoutes.add(currentTrip);

                    log.info("Added route for driver {} with {} elderly (total: {}) and time {}s",
                            currentDriver.id(), currentTrip.size(),
                            driverAssignedCounts.get(currentDriver), routeTime);

                    canAssignMore = true;
                }
            }
        }

        // 최종 배정 결과 로깅
        log.info("Clustering completed with {} routes", allRoutes.size());
        drivers.forEach(driver ->
                log.info("Driver {} final stats - Count: {}, Time: {}m {}s",
                        driver.id(),
                        driverAssignedCounts.get(driver),
                        driverTotalTimes.get(driver) / 60,
                        driverTotalTimes.get(driver) % 60));

        if (allRoutes.isEmpty()) {
            log.warn("No routes were created during clustering!");
            return null;
        }

        return allRoutes.stream()
                .map(route -> route.stream().mapToInt(Integer::intValue).toArray())
                .toArray(int[][]::new);
    }

    private int findNearestElderly(
            String currentLocation,
            List<Integer> possibleElderlys,
            Map<String, Map<String, Integer>> distanceMatrix,
            List<ElderlyDTO> elderlys
    ) {
        int nearestIndex = -1;
        int minDistance = Integer.MAX_VALUE;

        for (Integer elderlyIndex : possibleElderlys) {
            String elderlyId = "Elderly_" + elderlys.get(elderlyIndex).id();
            int distance = distanceMatrix.get(currentLocation).get(elderlyId);

            if (distance < minDistance) {
                minDistance = distance;
                nearestIndex = elderlyIndex;
            }
        }

        return nearestIndex;
    }
    private int calculateTotalRouteTime(
            List<Integer> route,
            Map<String, Map<String, Integer>> distanceMatrix,
            List<ElderlyDTO> elderlys,
            CompanyDTO company
    ) {
        if (route.isEmpty()) return 0;

        int totalTime = 0;
        String currentLocation = "Company";

        // 회사에서 시작하여 모든 어르신을 방문
        for (Integer elderlyIdx : route) {
            String elderlyId = "Elderly_" + elderlys.get(elderlyIdx).id();
            totalTime += distanceMatrix.get(currentLocation).get(elderlyId);
            currentLocation = elderlyId;
        }

        // 마지막 어르신에서 회사로 돌아오는 시간 추가
        totalTime += distanceMatrix.get(currentLocation).get("Company");

        return totalTime;
    }

    private List<Integer> optimizeRoute(
            List<Integer> elderlyIndices,
            Map<String, Map<String, Integer>> distanceMatrix,
            List<ElderlyDTO> elderlys,
            CompanyDTO company
    ) {
        if (elderlyIndices.size() <= 1) {
            return new ArrayList<>(elderlyIndices);
        }

        List<Integer> bestRoute = new ArrayList<>(elderlyIndices);
        int bestTime = calculateTotalRouteTime(bestRoute, distanceMatrix, elderlys, company);

        // 2-opt 알고리즘을 사용한 경로 최적화
        boolean improved = true;
        while (improved) {
            improved = false;
            for (int i = 0; i < bestRoute.size() - 1; i++) {
                for (int j = i + 1; j < bestRoute.size(); j++) {
                    List<Integer> newRoute = new ArrayList<>(bestRoute);
                    // 두 지점 사이의 경로를 뒤집음
                    reverse(newRoute, i, j);

                    int newTime = calculateTotalRouteTime(newRoute, distanceMatrix, elderlys, company);
                    if (newTime < bestTime) {
                        bestRoute = newRoute;
                        bestTime = newTime;
                        improved = true;
                    }
                }
            }
        }

        return bestRoute;
    }

    private void reverse(List<Integer> route, int from, int to) {
        while (from < to) {
            Collections.swap(route, from, to);
            from++;
            to--;
        }
    }
    public List<AssignmentResponseDTO> getOptimizedAssignmentsV2(RequestDispatchDTO requestDispatchDTO, String jobId)
            throws Exception {
        List<EmployeeDTO> employees = requestDispatchDTO.employees();
        List<ElderlyDTO> elderlys = requestDispatchDTO.elderlys();
        List<CoupleRequestDTO> couples = requestDispatchDTO.couples();
        CompanyDTO company = requestDispatchDTO.company();
        List<FixedAssignmentsDTO> fixedAssignments = requestDispatchDTO.fixedAssignments();

        sseService.notify(jobId, 5);

        Map<String, Map<String, Integer>> distanceMatrix = calculateDistanceMatrix(
                employees, elderlys, company, requestDispatchDTO.dispatchType(), jobId
        );

        sseService.notify(jobId, 15);

        int[][] clusterGenes = performDriverClustering(
                employees, elderlys, distanceMatrix, company
        );

        List<AssignmentResponseDTO> results = new ArrayList<>();

        if (clusterGenes != null && clusterGenes.length > 0) {
            Set<Long> assignedElderlyIds = new HashSet<>();
            List<EmployeeDTO> driverAssignments = new ArrayList<>();
            int driverCount = (int) employees.stream()
                    .filter(EmployeeDTO::isDriver)
                    .count();

            Map<Integer, List<Integer>> driverRoutes = new HashMap<>();
            for (int i = 0; i < clusterGenes.length; i++) {
                int driverIndex = i / 2;  // 각 운전원에게 연속으로 2개씩 배정
                if (driverIndex >= driverCount) break;

                driverRoutes.computeIfAbsent(driverIndex, k -> new ArrayList<>())
                        .add(i);
            }

            // 각 운전원별로 결과 생성
            for (int i = 0; i < driverCount; i++) {
                EmployeeDTO driver = employees.stream()
                        .filter(EmployeeDTO::isDriver)
                        .collect(Collectors.toList())
                        .get(i);

                List<Integer> routeIndices = driverRoutes.getOrDefault(i, new ArrayList<>());

                for (Integer routeIndex : routeIndices) {
                    driverAssignments.add(driver);
                    for (int elderlyIdx : clusterGenes[routeIndex]) {
                        assignedElderlyIds.add(elderlys.get(elderlyIdx).id());
                    }
                }
            }

            ChromosomeV3 clusterChromosome = new ChromosomeV3(clusterGenes);
            List<Double> clusterDepartureTimes = new ArrayList<>(
                    Collections.nCopies(clusterGenes.length, 0.0)
            );

            results.addAll(createResult(
                    driverAssignments,
                    elderlys,
                    clusterChromosome,
                    clusterDepartureTimes,
                    requestDispatchDTO.dispatchType()
            ));

            // 나머지 코드는 동일...
            List<EmployeeDTO> remainingEmployees = employees.stream()
                    .filter(e -> !e.isDriver())
                    .collect(Collectors.toList());

            List<ElderlyDTO> remainingElderlys = elderlys.stream()
                    .filter(e -> !assignedElderlyIds.contains(e.id()))
                    .collect(Collectors.toList());

            if (!remainingEmployees.isEmpty() && !remainingElderlys.isEmpty()) {
                GeneticAlgorithmV3 geneticAlgorithm = new GeneticAlgorithmV3(
                        remainingEmployees,
                        remainingElderlys,
                        couples,
                        fixedAssignments,
                        sseService
                );

                geneticAlgorithm.initialize(distanceMatrix, requestDispatchDTO.dispatchType(),
                        requestDispatchDTO.userName());

                List<ChromosomeV3> chromosomes = geneticAlgorithm.run(jobId);
                ChromosomeV3 bestChromosome = chromosomes.get(0);
                List<Double> departureTimes = bestChromosome.getDepartureTimes();

                results.addAll(createResult(
                        remainingEmployees,
                        remainingElderlys,
                        bestChromosome,
                        departureTimes,
                        requestDispatchDTO.dispatchType()
                ));
            }
        }

        sseService.notify(jobId, 95);
        dispatchHistoryService.saveDispatchResult(results);

        sseService.notify(jobId, 100);
        sseService.notifyResult(jobId, results);

        return results;
    }



    private List<AssignmentResponseDTO> createResult(List<EmployeeDTO> employees,
                                                     List<ElderlyDTO> elderlys, ChromosomeV3 bestChromosome,
                                                     List<Double> departureTimes, DispatchType dispatchType) {
        List<AssignmentResponseDTO> assignmentResponseDTOS = new ArrayList<>();

        for (int i = 0; i < employees.size(); i++) {
            List<AssignmentElderRequest> assignmentElders = new ArrayList<>();

            for (int j = 0; j < bestChromosome.getGenes()[i].length; j++) {
                assignmentElders.add(
                        new AssignmentElderRequest(elderlys.get(bestChromosome.getGenes()[i][j]).id(),
                                elderlys.get(bestChromosome.getGenes()[i][j]).homeAddress(),
                                elderlys.get(bestChromosome.getGenes()[i][j]).name()));
            }
            assignmentResponseDTOS.add(
                    new AssignmentResponseDTO(dispatchType, employees.get(i).id(), employees.get(i).homeAddress(),
                            employees.get(i).workplace(),
                            employees.get(i).name(),
                            (int) (departureTimes.get(i) - 0), assignmentElders));
        }
        return assignmentResponseDTOS;
    }

    private Map<String, Map<String, Integer>> calculateDistanceMatrix(List<EmployeeDTO> employees,
                                                                      List<ElderlyDTO> elderlys,
                                                                      CompanyDTO company, DispatchType dispatchType,
                                                                      String jobId) {

        long startTime = System.currentTimeMillis();
        log.info("jobId : {} / calculateDistanceMatrix start", jobId);
        Map<String, Map<String, Integer>> distanceMatrix = new HashMap<>();

        distanceMatrix.put("Company", new HashMap<>());

        for (EmployeeDTO employee : employees) {
            distanceMatrix.put("Employee_" + employee.id(), new HashMap<>());
        }

        for (ElderlyDTO elderly : elderlys) {
            distanceMatrix.put("Elderly_" + elderly.id(), new HashMap<>());
        }

        sseService.notify(jobId, 7.5);

        for (int i = 0; i < elderlys.size(); i++) {

            String startNodeId = "Company";
            String destinationNodeId = "Elderly_" + elderlys.get(i).id();

//            Optional<LinkDistance> linkDistance = linkDistanceRepository.findNodeByStartNodeIdAndDestinationNodeId(
//                    startNodeId, destinationNodeId);
//            if (linkDistance.isPresent()) {
//
//                if (dispatchType == DispatchType.DISTANCE_IN || dispatchType == DispatchType.DISTANCE_OUT) {
//                    distanceMatrix.get(startNodeId).put(destinationNodeId, linkDistance.get().getTotalDistance());
//                    distanceMatrix.get(destinationNodeId).put(startNodeId, linkDistance.get().getTotalDistance());
//                }
//
//                if (dispatchType == DispatchType.DURATION_IN || dispatchType == DispatchType.DURATION_OUT) {
//                    distanceMatrix.get(startNodeId).put(destinationNodeId, linkDistance.get().getTotalTime());
//                    distanceMatrix.get(destinationNodeId).put(startNodeId, linkDistance.get().getTotalTime());
//                }
//
//            } else {
            OsrmApiResponseDTO osrmApiResponseDTO = osrmService.getDistanceTotalTimeWithOsrmApi(
                    company.companyAddress(),
                    elderlys.get(i).homeAddress());
            log.info("{} : {} / {}", company, elderlys.get(i).homeAddressName(),
                    osrmApiResponseDTO.toString());

            if (dispatchType == DispatchType.DISTANCE_IN || dispatchType == DispatchType.DISTANCE_OUT) {
                distanceMatrix.get(startNodeId).put(destinationNodeId, osrmApiResponseDTO.distance());
                distanceMatrix.get(destinationNodeId).put(startNodeId, osrmApiResponseDTO.distance());
            }

            if (dispatchType == DispatchType.DURATION_IN || dispatchType == DispatchType.DURATION_OUT) {
                distanceMatrix.get(startNodeId).put(destinationNodeId, osrmApiResponseDTO.duration());
                distanceMatrix.get(destinationNodeId).put(startNodeId, osrmApiResponseDTO.duration());
            }

//                linkDistanceRepository.save(
//                        new LinkDistance(startNodeId, destinationNodeId, osrmApiResponseDTO.duration(),
//                                osrmApiResponseDTO.distance()));
//                linkDistanceRepository.save(
//                        new LinkDistance(destinationNodeId, startNodeId, osrmApiResponseDTO.duration(),
//                                osrmApiResponseDTO.distance()));
//            }

        }
        sseService.notify(jobId, 10);
        for (int i = 0; i < elderlys.size(); i++) {
            for (int j = 0; j < elderlys.size(); j++) {
                if (i == j) {
                    continue;
                }

                String startNodeId = "Elderly_" + elderlys.get(i).id();
                String destinationNodeId = "Elderly_" + elderlys.get(j).id();

//                Optional<LinkDistance> linkDistance = linkDistanceRepository.findNodeByStartNodeIdAndDestinationNodeId(
//                        startNodeId, destinationNodeId);
//
//                if (linkDistance.isPresent()) {
//
//                    if (dispatchType == DispatchType.DISTANCE_IN || dispatchType == DispatchType.DISTANCE_OUT) {
//                        distanceMatrix.get(startNodeId).put(destinationNodeId, linkDistance.get().getTotalDistance());
//                        distanceMatrix.get(destinationNodeId).put(startNodeId, linkDistance.get().getTotalDistance());
//                    }
//
//                    if (dispatchType == DispatchType.DURATION_IN || dispatchType == DispatchType.DURATION_OUT) {
//                        distanceMatrix.get(startNodeId).put(destinationNodeId, linkDistance.get().getTotalTime());
//                        distanceMatrix.get(destinationNodeId).put(startNodeId, linkDistance.get().getTotalTime());
//                    }
//
//                } else {
                OsrmApiResponseDTO osrmApiResponseDTO = osrmService.getDistanceTotalTimeWithOsrmApi(
                        elderlys.get(i).homeAddress(),
                        elderlys.get(j).homeAddress());

                log.info("{} : {} / {}", elderlys.get(i).homeAddressName(), elderlys.get(j).homeAddressName(),
                        osrmApiResponseDTO.toString());

                if (dispatchType == DispatchType.DISTANCE_IN || dispatchType == DispatchType.DISTANCE_OUT) {
                    distanceMatrix.get(startNodeId).put(destinationNodeId, osrmApiResponseDTO.distance());
                    distanceMatrix.get(destinationNodeId).put(startNodeId, osrmApiResponseDTO.distance());
                }

                if (dispatchType == DispatchType.DURATION_IN || dispatchType == DispatchType.DURATION_OUT) {
                    distanceMatrix.get(startNodeId).put(destinationNodeId, osrmApiResponseDTO.duration());
                    distanceMatrix.get(destinationNodeId).put(startNodeId, osrmApiResponseDTO.duration());
                }

//                    linkDistanceRepository.save(
//                            new LinkDistance(startNodeId, destinationNodeId, osrmApiResponseDTO.duration(),
//                                    osrmApiResponseDTO.distance()));
//                    linkDistanceRepository.save(
//                            new LinkDistance(destinationNodeId, startNodeId, osrmApiResponseDTO.duration(),
//                                    osrmApiResponseDTO.distance()));
//                }

            }

        }
        sseService.notify(jobId, 12.5);
        for (int i = 0; i < employees.size(); i++) {
            for (int j = 0; j < elderlys.size(); j++) {

                String startNodeId = "Employee_" + employees.get(i).id();
                String destinationNodeId = "Elderly_" + elderlys.get(j).id();

//                Optional<LinkDistance> linkDistance = linkDistanceRepository.findNodeByStartNodeIdAndDestinationNodeId(
//                        startNodeId, destinationNodeId);
//
//                if (linkDistance.isPresent()) {
//
//                    if (dispatchType == DispatchType.DISTANCE_IN || dispatchType == DispatchType.DISTANCE_OUT) {
//                        distanceMatrix.get(startNodeId).put(destinationNodeId, linkDistance.get().getTotalDistance());
//                        distanceMatrix.get(destinationNodeId).put(startNodeId, linkDistance.get().getTotalDistance());
//                    }
//
//                    if (dispatchType == DispatchType.DURATION_IN || dispatchType == DispatchType.DURATION_OUT) {
//                        distanceMatrix.get(startNodeId).put(destinationNodeId, linkDistance.get().getTotalTime());
//                        distanceMatrix.get(destinationNodeId).put(startNodeId, linkDistance.get().getTotalTime());
//                    }
//
//                } else {

                OsrmApiResponseDTO osrmApiResponseDTO = osrmService.getDistanceTotalTimeWithOsrmApi(
                        employees.get(i).homeAddress(),
                        elderlys.get(j).homeAddress());

                log.info("{} : {} / {}", employees.get(i).homeAddressName(), elderlys.get(j).homeAddressName(),
                        osrmApiResponseDTO.toString());

                if (dispatchType == DispatchType.DISTANCE_IN || dispatchType == DispatchType.DISTANCE_OUT) {
                    distanceMatrix.get(startNodeId).put(destinationNodeId, osrmApiResponseDTO.distance());
                    distanceMatrix.get(destinationNodeId).put(startNodeId, osrmApiResponseDTO.distance());
                }

                if (dispatchType == DispatchType.DURATION_IN || dispatchType == DispatchType.DURATION_OUT) {
                    distanceMatrix.get(startNodeId).put(destinationNodeId, osrmApiResponseDTO.duration());
                    distanceMatrix.get(destinationNodeId).put(startNodeId, osrmApiResponseDTO.duration());
                }

//                    linkDistanceRepository.save(
//                            new LinkDistance(startNodeId, destinationNodeId, osrmApiResponseDTO.duration(),
//                                    osrmApiResponseDTO.distance()));
//                    linkDistanceRepository.save(
//                            new LinkDistance(destinationNodeId, startNodeId, osrmApiResponseDTO.duration(),
//                                    osrmApiResponseDTO.distance()));
//                }
            }

        }
        sseService.notify(jobId, 15);

        long endTime = System.currentTimeMillis();
        log.info("jobId : {} / calculateDistanceMatrix end / execution time : {}ms",
                jobId,
                endTime - startTime);
        return distanceMatrix;
    }


}


