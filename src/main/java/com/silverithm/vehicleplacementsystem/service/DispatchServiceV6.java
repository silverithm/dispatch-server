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

    private String key;
    private String kakaoKey;


    public DispatchServiceV6(@Value("${tmap.key}") String key, @Value("${kakao.key}") String kakaoKey,
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
                || requestDispatchDTO.dispatchType() == DispatchType.DISTANCE_OUT ? 50000 : 2300;

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

            for (EmployeeDTO driver : drivers) {
                if (driverAssignedCounts.get(driver) >= driver.maximumCapacity()) {
                    continue;
                }

                List<Integer> currentRoute = new ArrayList<>();
                String currentLocation = "Company";
                int routeTime = 0;

                Iterator<ElderlyDistance> iterator = availableElderly.iterator();
                while (iterator.hasNext()) {
                    ElderlyDistance elderly = iterator.next();
                    String elderlyId = "Elderly_" + elderlys.get(elderly.index).id();
                    int timeToElderly = distanceMatrix.get(currentLocation).get(elderlyId);
                    int timeToCompany = distanceMatrix.get(elderlyId).get("Company");
                    int potentialTotalTime = driverTotalTimes.get(driver) + routeTime + timeToElderly + timeToCompany;

                    if (potentialTotalTime <= TIME_LIMIT &&
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

        // 2단계: 남은 인원을 기존 운전원의 남은 공간에 배정
        if (!availableElderly.isEmpty()) {
            log.info("Attempting to fill remaining capacity for existing drivers with {} elderly",
                    availableElderly.size());

            for (EmployeeDTO driver : drivers) {
                // 현재 운전원의 총 배정된 인원 확인
                int currentTotal = driverAssignedCounts.get(driver);
                int remainingCapacity = driver.maximumCapacity() - currentTotal;

                if (remainingCapacity <= 0 || availableElderly.isEmpty()) {
                    continue;
                }

                // 마지막 route 가져오기
                List<List<Integer>> driverCurrentRoutes = driverRoutes.get(driver);
                List<Integer> lastRoute = driverCurrentRoutes.isEmpty() ? new ArrayList<>()
                        : driverCurrentRoutes.get(driverCurrentRoutes.size() - 1);

                // 남은 수용 인원만큼 추가
                int added = 0;
                Iterator<ElderlyDistance> iterator = availableElderly.iterator();
                while (iterator.hasNext() && added < remainingCapacity) {
                    ElderlyDistance elderly = iterator.next();
                    lastRoute.add(elderly.index);
                    iterator.remove();
                    added++;
                }

                if (added > 0) {
                    // 기존 route가 없었다면 새로 추가
                    if (driverCurrentRoutes.isEmpty()) {
                        driverRoutes.get(driver).add(lastRoute);
                    }
                    driverAssignedCounts.merge(driver, added, Integer::sum);
                    totalAssigned += added;
                    log.info("Added {} extra elderly to existing route for driver {} (total: {})",
                            added, driver.id(), driverAssignedCounts.get(driver));
                }
            }
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

    private int[][] assignDriversFirst(
            List<EmployeeDTO> employees,
            List<ElderlyDTO> elderlys,
            Map<String, Map<String, Integer>> distanceMatrix,
            DispatchType dispatchType
    ) {
        List<EmployeeDTO> drivers = employees.stream()
                .filter(EmployeeDTO::isDriver)
                .collect(Collectors.toList());

        if (drivers.isEmpty()) {
            return null;
        }

        final int TIME_LIMIT = 3600; // 1시간(3600초) 제한
        List<List<Integer>> allRoutes = new ArrayList<>();
        Set<Integer> assignedElderlyIndices = new HashSet<>();

        // 모든 어르신과 회사 간의 거리를 계산하고 정렬
        List<ElderlyWithDistance> elderlyDistances = new ArrayList<>();
        for (int i = 0; i < elderlys.size(); i++) {
            String elderlyId = "Elderly_" + elderlys.get(i).id();
            int distanceOrDuration =
                    dispatchType == DispatchType.DISTANCE_IN || dispatchType == DispatchType.DISTANCE_OUT ?
                            distanceMatrix.get("Company").get(elderlyId) :
                            distanceMatrix.get("Company").get(elderlyId);
            elderlyDistances.add(new ElderlyWithDistance(i, distanceOrDuration));
        }

        // 거리가 먼 순서대로 정렬
        elderlyDistances.sort((a, b) -> Integer.compare(b.distance, a.distance));

        // 각 운전원별로 배정
        for (EmployeeDTO driver : drivers) {
            List<Integer> currentRoute = new ArrayList<>();
            String currentLocation = "Company";
            int currentTime = 0;

            // 가장 먼 거리의 어르신부터 배정 시도
            for (ElderlyWithDistance elderly : elderlyDistances) {
                if (assignedElderlyIndices.contains(elderly.index)) {
                    continue;
                }

                String elderlyId = "Elderly_" + elderlys.get(elderly.index).id();
                int timeToElderly = distanceMatrix.get(currentLocation).get(elderlyId);
                int timeToCompany = distanceMatrix.get(elderlyId).get("Company");
                int potentialTotalTime = currentTime + timeToElderly + timeToCompany;

                // 시간 제한을 넘지 않고 운전원의 최대 수용 인원을 넘지 않는 경우에만 배정
                if (potentialTotalTime <= TIME_LIMIT && currentRoute.size() < driver.maximumCapacity()) {
                    currentRoute.add(elderly.index);
                    currentTime = potentialTotalTime;
                    currentLocation = elderlyId;
                    assignedElderlyIndices.add(elderly.index);
                }
            }

            if (!currentRoute.isEmpty()) {
                // 경로 최적화
                List<Integer> optimizedRoute = optimizeDriverRoute(
                        currentRoute,
                        distanceMatrix,
                        elderlys
                );
                allRoutes.add(optimizedRoute);
            }
        }

        // 결과를 2차원 배열로 변환
        return allRoutes.stream()
                .map(route -> route.stream().mapToInt(Integer::intValue).toArray())
                .toArray(int[][]::new);
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
            OsrmApiResponseDTO osrmApiResponseDTO = osrmService.getDistanceTotalTimeWithOsrmApi(
                    company.companyAddress(),
                    elderlys.get(i).homeAddress());
//            log.info("{} : {} / {}", company, elderlys.get(i).homeAddressName(),
//                    osrmApiResponseDTO.toString());

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

//                log.info("{} : {} / {}", elderlys.get(i).homeAddressName(), elderlys.get(j).homeAddressName(),
//                        osrmApiResponseDTO.toString());

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

//                log.info("{} : {} / {}", employees.get(i).homeAddressName(), elderlys.get(j).homeAddressName(),
//                        osrmApiResponseDTO.toString());

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


