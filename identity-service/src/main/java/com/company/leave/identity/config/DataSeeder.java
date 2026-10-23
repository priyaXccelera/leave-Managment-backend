package com.company.leave.identity.config;

import com.company.leave.identity.domain.Employee;
import com.company.leave.identity.domain.Role;
import com.company.leave.identity.repo.EmployeeRepository;
import com.company.leave.identity.service.LeaveBalanceClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Idempotently seeds the demo accounts and then, in the background, makes sure leave-service has balances for them.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DataSeeder.class);
    private static final int INIT_ATTEMPTS = 30;
    private static final long INIT_DELAY_MILLIS = 2000;

    private record Seed(String code, String name, String email, Role role, String department, String designation,
                        LocalDate joined, String managerCode) {
    }

    private static final List<Seed> SEEDS = List.of(
            new Seed("HR001", "Hannah Rivers", "hr@company.com", Role.HR, "Human Resources", "HR Manager",
                    LocalDate.of(2023, 1, 2), null),
            new Seed("MGR001", "Mark Fischer", "manager1@company.com", Role.MANAGER, "Engineering",
                    "Engineering Manager", LocalDate.of(2023, 1, 2), null),
            new Seed("MGR002", "Maria Lopez", "manager2@company.com", Role.MANAGER, "Engineering",
                    "Engineering Manager", LocalDate.of(2023, 1, 2), null),
            new Seed("EMP001", "Alice Johnson", "emp1@company.com", Role.EMPLOYEE, "Engineering",
                    "Software Engineer", LocalDate.of(2023, 2, 1), "MGR001"),
            new Seed("EMP002", "Bob Smith", "emp2@company.com", Role.EMPLOYEE, "Engineering",
                    "Software Engineer", LocalDate.of(2023, 3, 1), "MGR001"),
            new Seed("EMP003", "Carol White", "emp3@company.com", Role.EMPLOYEE, "Engineering",
                    "QA Engineer", LocalDate.of(2023, 4, 3), "MGR001"),
            new Seed("EMP004", "David Brown", "emp4@company.com", Role.EMPLOYEE, "Engineering",
                    "Software Engineer", LocalDate.of(2023, 5, 2), "MGR002"),
            new Seed("EMP005", "Eva Green", "emp5@company.com", Role.EMPLOYEE, "Engineering",
                    "DevOps Engineer", LocalDate.of(2023, 6, 1), "MGR002"));

    private final EmployeeRepository employees;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;
    private final LeaveBalanceClient leaveBalanceClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "leave-balance-seed-retry");
        t.setDaemon(true);
        return t;
    });

    public DataSeeder(EmployeeRepository employees, PasswordEncoder passwordEncoder, AppProperties props,
                      LeaveBalanceClient leaveBalanceClient) {
        this.employees = employees;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
        this.leaveBalanceClient = leaveBalanceClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<Long, LocalDate> seeded = seed();
        if (!seeded.isEmpty()) {
            executor.submit(() -> initBalancesWithRetry(seeded));
        }
    }

    public Map<Long, LocalDate> seed() {
        Map<String, Employee> byCode = new LinkedHashMap<>();
        for (Seed s : SEEDS) {
            Employee e = employees.findByEmployeeCodeIgnoreCase(s.code()).orElse(null);
            if (e == null) {
                e = create(s);
            }
            if (e != null) {
                byCode.put(s.code(), e);
            }
        }
        // managers are assigned after every account exists
        for (Seed s : SEEDS) {
            Employee e = byCode.get(s.code());
            Employee manager = s.managerCode() == null ? null : byCode.get(s.managerCode());
            if (e != null && manager != null && e.getManagerId() == null) {
                e.setManagerId(manager.getId());
                employees.save(e);
            }
        }
        Map<Long, LocalDate> result = new LinkedHashMap<>();
        byCode.values().forEach(e -> result.put(e.getId(), e.getDateOfJoining()));
        LOG.info("Seed check complete: {} seed accounts present", result.size());
        return result;
    }

    private Employee create(Seed s) {
        Employee e = new Employee();
        e.setEmployeeCode(s.code());
        e.setName(s.name());
        e.setEmail(s.email());
        e.setPasswordHash(passwordEncoder.encode(props.seedPassword()));
        e.setRole(s.role());
        e.setDepartment(s.department());
        e.setDesignation(s.designation());
        e.setDateOfJoining(s.joined());
        e.setActive(true);
        try {
            return employees.save(e);
        } catch (DataIntegrityViolationException ex) {
            // another instance seeded concurrently, or the email is already taken
            LOG.warn("Could not seed {}: {}", s.code(), ex.getMostSpecificCause().getMessage());
            return employees.findByEmployeeCodeIgnoreCase(s.code()).orElse(null);
        }
    }

    private void initBalancesWithRetry(Map<Long, LocalDate> seeded) {
        Map<Long, LocalDate> pending = new LinkedHashMap<>(seeded);
        for (int attempt = 1; attempt <= INIT_ATTEMPTS && !pending.isEmpty(); attempt++) {
            for (Map.Entry<Long, LocalDate> entry : new ArrayList<>(pending.entrySet())) {
                try {
                    leaveBalanceClient.initBalances(entry.getKey(), entry.getValue());
                    pending.remove(entry.getKey());
                } catch (RuntimeException ex) {
                    LOG.warn("Leave balance init for employee {} failed (attempt {}/{}): {}",
                            entry.getKey(), attempt, INIT_ATTEMPTS, ex.getMessage());
                    break; // leave-service is probably not up yet; wait and retry from the start
                }
            }
            if (pending.isEmpty()) {
                break;
            }
            try {
                Thread.sleep(INIT_DELAY_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                LOG.info("Leave balance init retry interrupted; {} employees still pending", pending.size());
                return;
            }
        }
        if (pending.isEmpty()) {
            LOG.info("Leave balances initialised for all seeded employees");
        } else {
            LOG.error("Gave up initialising leave balances for {} seeded employees", pending.size());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
