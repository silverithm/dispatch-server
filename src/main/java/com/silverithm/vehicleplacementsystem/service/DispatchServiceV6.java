package com.silverithm.vehicleplacementsystem.service;

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
import com.silverithm.vehicleplacementsystem.repository.LinkDistanceRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
public class DispatchServiceV6 {


    private final LinkDistanceRepository linkDistanceRepository;
    private final SSEService sseService;
    private final DispatchHistoryService dispatchHistoryService;
    private final OsrmService osrmService;
    private final KakaoMapApiService kakaoMapApiService;

    private String key;
    private String kakaoKey;


    public DispatchServiceV6(@Value("${tmap.key}") String key, @Value("${kakao.key}") String kakaoKey,
                             LinkDistanceRepository linkDistanceRepository,
                             SSEService sseService, DispatchHistoryService dispatchHistoryService,
                             OsrmService osrmService, KakaoMapApiService kakaoMapApiService
    ) {
        this.linkDistanceRepository = linkDistanceRepository;
        this.sseService = sseService;
        this.key = key;
        this.kakaoKey = kakaoKey;
        this.dispatchHistoryService = dispatchHistoryService;
        this.osrmService = osrmService;
        this.kakaoMapApiService = kakaoMapApiService;
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
        GeneticAlgorithmV6 geneticAlgorithm = new GeneticAlgorithmV6(employees, elderlys,
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
            CompanyDTO company,
            RequestDispatchDTO requestDispatchDTO
    ) {
        List<EmployeeDTO> drivers = employees.stream()
                .filter(EmployeeDTO::isDriver)
                .collect(Collectors.toList());

        if (drivers.isEmpty()) {
            return null;
        }

        int TIME_LIMIT = requestDispatchDTO.dispatchType() == DispatchType.DISTANCE_IN
                || requestDispatchDTO.dispatchType() == DispatchType.DISTANCE_OUT ? 30000 : 3600;

        Map<EmployeeDTO, Integer> driverTotalTimes = new HashMap<>();
        Map<EmployeeDTO, Integer> driverAssignedCounts = new HashMap<>();
        drivers.forEach(driver -> {
            driverTotalTimes.put(driver, 0);
            driverAssignedCounts.put(driver, 0);
        });

        // 거리순으로 어르신 정렬
        List<ElderlyDistance> availableElderly = new ArrayList<>();
        for (int i = 0; i < elderlys.size(); i++) {
            String elderlyId = "Elderly_" + elderlys.get(i).id();
            int distanceFromCompany = distanceMatrix.get("Company").get(elderlyId);
            availableElderly.add(new ElderlyDistance(i, distanceFromCompany));
        }
        availableElderly.sort((a, b) -> Integer.compare(b.distance, a.distance));

        List<List<Integer>> allRoutes = new ArrayList<>();
        Map<EmployeeDTO, List<List<Integer>>> driverRoutes = new HashMap<>();
        drivers.forEach(driver -> driverRoutes.put(driver, new ArrayList<>()));

        int totalAssigned = 0;
        int attempts = 0;
        int maxAttempts = drivers.size() * 3;

        // 1단계: 시간 제한 내에서 최대한 배치 시도
        while (attempts < maxAttempts && !availableElderly.isEmpty()) {
            attempts++;
            boolean assignedInThisRound = false;

            // 가장 멀리 있는 어르신부터 처리하기 위해 드라이버들을 순회
            for (EmployeeDTO driver : drivers) {
                if (driverAssignedCounts.get(driver) >= driver.maximumCapacity()) {
                    continue;
                }

                List<Integer> currentRoute = new ArrayList<>();
                String currentLocation = "Company";
                int routeTime = 0;

                // 가장 먼 거리의 어르신을 먼저 시도
                Iterator<ElderlyDistance> iterator = availableElderly.iterator();
                while (iterator.hasNext()) {
                    ElderlyDistance elderly = iterator.next();
                    String elderlyId = "Elderly_" + elderlys.get(elderly.index).id();

                    // 시간 제한을 체크하기 전에 거리가 특정 임계값 이상인 경우 우선 배정 시도
                    int distanceFromCompany = distanceMatrix.get("Company").get(elderlyId);
                    boolean isPriorityElderly = distanceFromCompany > TIME_LIMIT * 0.7; // 예: 70% 이상 거리는 우선 배정

                    int timeToElderly = distanceMatrix.get(currentLocation).get(elderlyId);
                    int timeToCompany = distanceMatrix.get(elderlyId).get("Company");
                    int potentialTotalTime = driverTotalTimes.get(driver) + routeTime + timeToElderly + timeToCompany;

                    // 우선순위 어르신이면 시간 제한을 좀 더 여유있게 적용
                    int effectiveTimeLimit = isPriorityElderly ? (int)(TIME_LIMIT * 1.1) : TIME_LIMIT;

                    if ((potentialTotalTime <= effectiveTimeLimit || isPriorityElderly) &&
                            currentRoute.size() < driver.maximumCapacity()) {
                        currentRoute.add(elderly.index);
                        routeTime += timeToElderly;
                        currentLocation = elderlyId;
                        iterator.remove();
                        assignedInThisRound = true;
                    }
                }

                if (!currentRoute.isEmpty()) {
                    routeTime += distanceMatrix.get(currentLocation).get("Company");
                    driverTotalTimes.put(driver, driverTotalTimes.get(driver) + routeTime);
                    driverAssignedCounts.merge(driver, currentRoute.size(), Integer::sum);
                    totalAssigned += currentRoute.size();

                    List<Integer> optimizedRoute = optimizeRoute(currentRoute, distanceMatrix, elderlys, company);
                    driverRoutes.get(driver).add(optimizedRoute);

                    log.info("Added route for driver {} with {} elderly (total: {}) and time {}s",
                            driver.id(), currentRoute.size(),
                            driverAssignedCounts.get(driver), routeTime);
                }
            }

            if (!assignedInThisRound) {
                break;
            }
        }

        if (!availableElderly.isEmpty()) {
            log.info("Attempting to fill remaining capacity for existing drivers with {} elderly",
                    availableElderly.size());

            // 비운전원의 총 처리 가능 인원 계산
            int nonDriverCapacity = employees.stream()
                    .filter(emp -> !emp.isDriver())
                    .mapToInt(EmployeeDTO::maximumCapacity)
                    .sum();

            // 현재 배정된 인원과 남은 인원 계산
            int remainingElderly = availableElderly.size();

            // 추가로 운전원에게 배정해야 할 인원 계산 (음수면 0으로 처리)
            int additionalRequired = Math.max(0, remainingElderly - nonDriverCapacity);

            log.info("Non-driver capacity: {}, Remaining elderly: {}, Additional required: {}",
                    nonDriverCapacity, remainingElderly, additionalRequired);

            if (additionalRequired > 0) {
                // 추가 필요 인원만 운전원에게 배정
                for (EmployeeDTO driver : drivers) {
                    if (additionalRequired <= 0 || availableElderly.isEmpty()) {
                        break;
                    }

                    int currentTotal = driverAssignedCounts.get(driver);
                    int remainingCapacity = driver.maximumCapacity() - currentTotal;

                    if (remainingCapacity <= 0) {
                        continue;
                    }

                    // 현재 운전원에게 배정할 추가 인원 계산
                    int toAssign = Math.min(remainingCapacity, additionalRequired);

                    List<List<Integer>> driverCurrentRoutes = driverRoutes.get(driver);
                    List<Integer> lastRoute = driverCurrentRoutes.isEmpty() ? new ArrayList<>()
                            : driverCurrentRoutes.get(driverCurrentRoutes.size() - 1);

                    int added = 0;
                    Iterator<ElderlyDistance> iterator = availableElderly.iterator();
                    while (iterator.hasNext() && added < toAssign) {
                        ElderlyDistance elderly = iterator.next();
                        lastRoute.add(elderly.index);
                        iterator.remove();
                        added++;
                    }

                    if (added > 0) {
                        if (driverCurrentRoutes.isEmpty()) {
                            driverRoutes.get(driver).add(lastRoute);
                        }
                        driverAssignedCounts.merge(driver, added, Integer::sum);
                        totalAssigned += added;
                        additionalRequired -= added;

                        log.info("Added {} additional elderly to driver {} (total: {}, remaining to assign: {})",
                                added, driver.id(), driverAssignedCounts.get(driver), additionalRequired);
                    }
                }
            }

            log.info("Unassigned elderly for non-drivers: {}", availableElderly.size());
        }


        // 모든 route를 하나의 리스트로 합치기
        allRoutes = drivers.stream()
                .flatMap(driver -> driverRoutes.get(driver).stream())
                .collect(Collectors.toList());

        log.info("Clustering completed with {} routes, {} elderly assigned (total: {})",
                allRoutes.size(), totalAssigned, elderlys.size());

        drivers.forEach(driver ->
                log.info("Driver {} final stats - Count: {}, Time: {}m {}s",
                        driver.id(),
                        driverAssignedCounts.get(driver),
                        driverTotalTimes.get(driver) / 60,
                        driverTotalTimes.get(driver) % 60));

        if (totalAssigned < elderlys.size()) {
            log.info("Driver clustering completed. Remaining elderly for genetic algorithm: {}",
                    elderlys.size() - totalAssigned);
        }

        return allRoutes.stream()
                .map(route -> route.stream().mapToInt(Integer::intValue).toArray())
                .toArray(int[][]::new);
    }

    private static class ElderlyDistance {
        final int index;
        final int distance;

        ElderlyDistance(int index, int distance) {
            this.index = index;
            this.distance = distance;
        }
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
        if (route.isEmpty()) {
            return 0;
        }

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

    private List<Integer> optimizeDriverRoute(
            List<Integer> route,
            Map<String, Map<String, Integer>> distanceMatrix,
            List<ElderlyDTO> elderlys
    ) {
        if (route.size() <= 2) {
            return route;
        }

        List<Integer> bestRoute = new ArrayList<>(route);
        int bestTime = calculateRouteTime(bestRoute, distanceMatrix, elderlys);

        // 2-opt 최적화
        boolean improved = true;
        while (improved) {
            improved = false;
            for (int i = 0; i < bestRoute.size() - 1; i++) {
                for (int j = i + 1; j < bestRoute.size(); j++) {
                    List<Integer> newRoute = new ArrayList<>(bestRoute);
                    // 두 지점 사이의 경로를 뒤집음
                    reverse(newRoute, i, j);

                    int newTime = calculateRouteTime(newRoute, distanceMatrix, elderlys);
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

    private int calculateRouteTime(
            List<Integer> route,
            Map<String, Map<String, Integer>> distanceMatrix,
            List<ElderlyDTO> elderlys
    ) {
        if (route.isEmpty()) {
            return 0;
        }

        int totalTime = 0;
        String currentLocation = "Company";

        for (Integer elderlyIdx : route) {
            String elderlyId = "Elderly_" + elderlys.get(elderlyIdx).id();
            totalTime += distanceMatrix.get(currentLocation).get(elderlyId);
            currentLocation = elderlyId;
        }

        totalTime += distanceMatrix.get(currentLocation).get("Company");
        return totalTime;
    }

    private static class ElderlyWithDistance {
        final int index;
        final int distance;

        ElderlyWithDistance(int index, int distance) {
            this.index = index;
            this.distance = distance;
        }
    }

    // getOptimizedAssignmentsV2 메소드 수정
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

        // 운전원 우선 배정 실행
        int[][] driverAssignedGenes = performDriverClustering(
                employees, elderlys, distanceMatrix, company, requestDispatchDTO
        );
        List<AssignmentResponseDTO> results = new ArrayList<>();

        log.info("Driver assigned genes : {}", driverAssignedGenes);

        results.forEach(result -> {
            log.info("result : {}", result);
        });

        if (driverAssignedGenes != null && driverAssignedGenes.length > 0) {
            Set<Long> assignedElderlyIds = new HashSet<>();
            List<EmployeeDTO> driverAssignments = new ArrayList<>();

            // 운전원 배정 결과 처리
            List<EmployeeDTO> drivers = employees.stream()
                    .filter(EmployeeDTO::isDriver)
                    .collect(Collectors.toList());

            for (int i = 0; i < driverAssignedGenes.length; i++) {
                EmployeeDTO driver = drivers.get(i % drivers.size());
                driverAssignments.add(driver);

                for (int elderlyIdx : driverAssignedGenes[i]) {
                    assignedElderlyIds.add(elderlys.get(elderlyIdx).id());
                }
            }

            ChromosomeV3 driverChromosome = new ChromosomeV3(driverAssignedGenes);
            List<Double> departureTimes = new ArrayList<>(
                    Collections.nCopies(driverAssignedGenes.length, 0.0)
            );

            results.addAll(createResult(
                    driverAssignments,
                    elderlys,
                    driverChromosome,
                    departureTimes,
                    requestDispatchDTO.dispatchType()
            ));

            // 남은 직원과 어르신 처리
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
                List<Double> departureTimesResult = bestChromosome.getDepartureTimes();

                results.addAll(createResult(
                        remainingEmployees,
                        remainingElderlys,
                        bestChromosome,
                        departureTimesResult,
                        requestDispatchDTO.dispatchType()
                ));
            }
        }

        sseService.notify(jobId, 95);
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
                            (int) (departureTimes.get(i) - 0), assignmentElders, employees.get(i).isDriver()));
        }
        return assignmentResponseDTOS;
    }

    private Map<String, Map<String, Integer>> calculateDistanceMatrix(List<EmployeeDTO> employees,
                                                                      List<ElderlyDTO> elderlys,
                                                                      CompanyDTO company, DispatchType dispatchType,
                                                                      String jobId) {

        long startTime = System.currentTimeMillis();
//        log.info("jobId : {} / calculateDistanceMatrix start", jobId);
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
            KakaoMapApiResponseDTO kakaoMapApiResponseDTO = kakaoMapApiService.getDistanceTotalTimeWithKakaoMapApi(
                    company.companyAddress(),
                    elderlys.get(i).homeAddress());
//            log.info("{} : {} / {}", company, elderlys.get(i).homeAddressName(),
//                    osrmApiResponseDTO.toString());

            if (dispatchType == DispatchType.DISTANCE_IN || dispatchType == DispatchType.DISTANCE_OUT) {
                distanceMatrix.get(startNodeId).put(destinationNodeId, kakaoMapApiResponseDTO.distance());
                distanceMatrix.get(destinationNodeId).put(startNodeId, kakaoMapApiResponseDTO.distance());
            }

            if (dispatchType == DispatchType.DURATION_IN || dispatchType == DispatchType.DURATION_OUT) {
                distanceMatrix.get(startNodeId).put(destinationNodeId, kakaoMapApiResponseDTO.duration());
                distanceMatrix.get(destinationNodeId).put(startNodeId, kakaoMapApiResponseDTO.duration());
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
                KakaoMapApiResponseDTO kakaoMapApiResponseDTO = kakaoMapApiService.getDistanceTotalTimeWithKakaoMapApi(
                        elderlys.get(i).homeAddress(),
                        elderlys.get(j).homeAddress());

//                log.info("{} : {} / {}", elderlys.get(i).homeAddressName(), elderlys.get(j).homeAddressName(),
//                        osrmApiResponseDTO.toString());

                if (dispatchType == DispatchType.DISTANCE_IN || dispatchType == DispatchType.DISTANCE_OUT) {
                    distanceMatrix.get(startNodeId).put(destinationNodeId, kakaoMapApiResponseDTO.distance());
                    distanceMatrix.get(destinationNodeId).put(startNodeId, kakaoMapApiResponseDTO.distance());
                }

                if (dispatchType == DispatchType.DURATION_IN || dispatchType == DispatchType.DURATION_OUT) {
                    distanceMatrix.get(startNodeId).put(destinationNodeId, kakaoMapApiResponseDTO.duration());
                    distanceMatrix.get(destinationNodeId).put(startNodeId, kakaoMapApiResponseDTO.duration());
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

                KakaoMapApiResponseDTO kakaoMapApiResponseDTO = kakaoMapApiService.getDistanceTotalTimeWithKakaoMapApi(
                        employees.get(i).homeAddress(),
                        elderlys.get(j).homeAddress());

//                log.info("{} : {} / {}", employees.get(i).homeAddressName(), elderlys.get(j).homeAddressName(),
//                        osrmApiResponseDTO.toString());

                if (dispatchType == DispatchType.DISTANCE_IN || dispatchType == DispatchType.DISTANCE_OUT) {
                    distanceMatrix.get(startNodeId).put(destinationNodeId, kakaoMapApiResponseDTO.distance());
                    distanceMatrix.get(destinationNodeId).put(startNodeId, kakaoMapApiResponseDTO.distance());
                }

                if (dispatchType == DispatchType.DURATION_IN || dispatchType == DispatchType.DURATION_OUT) {
                    distanceMatrix.get(startNodeId).put(destinationNodeId, kakaoMapApiResponseDTO.duration());
                    distanceMatrix.get(destinationNodeId).put(startNodeId, kakaoMapApiResponseDTO.duration());
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


