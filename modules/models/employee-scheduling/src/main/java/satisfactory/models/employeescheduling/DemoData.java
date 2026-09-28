/*
 * Ported from Timefold Quickstarts (use-cases/employee-scheduling DemoDataGenerator), Apache License 2.0.
 * The schedule starts on a fixed Monday rather than the next one, so a demo dataset is the same every
 * time it is generated.
 */
package satisfactory.models.employeescheduling;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import satisfactory.models.employeescheduling.domain.Employee;
import satisfactory.models.employeescheduling.domain.EmployeeSchedule;
import satisfactory.models.employeescheduling.domain.Shift;
import satisfactory.spi.DemoDataset;

final class DemoData {

    record CountDistribution(int count, double weight) {
    }

    record Parameters(List<String> locations, List<String> requiredSkills, List<String> optionalSkills,
            int daysInSchedule, int employeeCount, List<CountDistribution> optionalSkillDistribution,
            List<CountDistribution> shiftCountDistribution, List<CountDistribution> availabilityCountDistribution,
            int randomSeed) {
    }

    static final LocalDate START = LocalDate.of(2026, 10, 5);

    static final Parameters SMALL = new Parameters(
            List.of("Ambulatory care", "Critical care", "Pediatric care"),
            List.of("Doctor", "Nurse"),
            List.of("Anaesthetics", "Cardiology"),
            14, 15,
            List.of(new CountDistribution(1, 3), new CountDistribution(2, 1)),
            List.of(new CountDistribution(1, 0.9), new CountDistribution(2, 0.1)),
            List.of(new CountDistribution(1, 4), new CountDistribution(2, 3), new CountDistribution(3, 2),
                    new CountDistribution(4, 1)),
            0);

    static final Parameters LARGE = new Parameters(
            List.of("Ambulatory care", "Neurology", "Critical care", "Pediatric care", "Surgery", "Radiology",
                    "Outpatient"),
            List.of("Doctor", "Nurse"),
            List.of("Anaesthetics", "Cardiology", "Radiology"),
            28, 50,
            List.of(new CountDistribution(1, 3), new CountDistribution(2, 1)),
            List.of(new CountDistribution(1, 0.5), new CountDistribution(2, 0.3), new CountDistribution(3, 0.2)),
            List.of(new CountDistribution(5, 4), new CountDistribution(10, 3), new CountDistribution(15, 2),
                    new CountDistribution(20, 1)),
            0);

    static final List<DemoDataset> ALL = List.of(
            new DemoDataset("SMALL", "15 employees, 2 weeks",
                    "Three hospital locations over fourteen days: fifteen employees, doctors and nurses, some with extra skills and some with unavailable, undesired or desired days.",
                    List.of("small", "hospital"), Map.of("spentLimit", "PT30S"), () -> encode(SMALL)),
            new DemoDataset("LARGE", "50 employees, 4 weeks",
                    "Seven hospital locations over twenty-eight days with fifty employees; a realistic monthly roster.",
                    List.of("large", "hospital"), Map.of("spentLimit", "PT5M"), () -> encode(LARGE)));

    private static final String[] FIRST_NAMES = { "Amy", "Beth", "Carl", "Dan", "Elsa", "Flo", "Gus", "Hugo", "Ivy",
            "Jay" };
    private static final String[] LAST_NAMES = { "Cole", "Fox", "Green", "Jones", "King", "Li", "Poe", "Rye", "Smith",
            "Watt" };
    private static final Duration SHIFT_LENGTH = Duration.ofHours(8);
    private static final LocalTime[][] SHIFT_START_TIMES_COMBOS = {
            { LocalTime.of(6, 0), LocalTime.of(14, 0) },
            { LocalTime.of(6, 0), LocalTime.of(14, 0), LocalTime.of(22, 0) },
            { LocalTime.of(6, 0), LocalTime.of(9, 0), LocalTime.of(14, 0), LocalTime.of(22, 0) },
    };

    private DemoData() {
    }

    static JsonNode encode(Parameters parameters) {
        return EmployeeSchedulingCodecs.encode(generate(parameters));
    }

    static EmployeeSchedule generate(Parameters parameters) {
        Random random = new Random(parameters.randomSeed());
        Map<String, List<LocalTime>> startTimes = new HashMap<>();
        int template = 0;
        for (String location : parameters.locations()) {
            startTimes.put(location, List.of(SHIFT_START_TIMES_COMBOS[template]));
            template = (template + 1) % SHIFT_START_TIMES_COMBOS.length;
        }
        List<String> names = joinAllCombinations(FIRST_NAMES, LAST_NAMES);
        Collections.shuffle(names, random);
        List<Employee> employees = new ArrayList<>();
        for (int i = 0; i < parameters.employeeCount(); i++) {
            Set<String> skills = new LinkedHashSet<>(
                    pickSubset(parameters.optionalSkills(), random, parameters.optionalSkillDistribution()));
            skills.add(pickRandom(parameters.requiredSkills(), random));
            employees.add(new Employee(names.get(i), skills, new LinkedHashSet<>(), new LinkedHashSet<>(),
                    new LinkedHashSet<>()));
        }
        List<Shift> shifts = new ArrayList<>();
        for (int day = 0; day < parameters.daysInSchedule(); day++) {
            LocalDate date = START.plusDays(day);
            for (Employee employee : pickSubset(employees, random, parameters.availabilityCountDistribution())) {
                switch (random.nextInt(3)) {
                    case 0 -> employee.getUnavailableDates().add(date);
                    case 1 -> employee.getUndesiredDates().add(date);
                    default -> employee.getDesiredDates().add(date);
                }
            }
            for (String location : parameters.locations()) {
                for (LocalTime startTime : startTimes.get(location)) {
                    LocalDateTime start = date.atTime(startTime);
                    int count = pickCount(random, parameters.shiftCountDistribution());
                    for (int i = 0; i < count; i++) {
                        String skill = random.nextBoolean()
                                ? pickRandom(parameters.requiredSkills(), random)
                                : pickRandom(parameters.optionalSkills(), random);
                        shifts.add(new Shift(null, start, start.plus(SHIFT_LENGTH), location, skill, null));
                    }
                }
            }
        }
        for (int i = 0; i < shifts.size(); i++) {
            shifts.get(i).setId(Integer.toString(i));
        }
        return new EmployeeSchedule(employees, shifts);
    }

    private static <T> T pickRandom(List<T> source, Random random) {
        return source.get(random.nextInt(source.size()));
    }

    private static int pickCount(Random random, List<CountDistribution> distribution) {
        double sum = distribution.stream().mapToDouble(CountDistribution::weight).sum();
        double choice = random.nextDouble(sum);
        int i = 0;
        while (choice >= distribution.get(i).weight()) {
            choice -= distribution.get(i).weight();
            i++;
        }
        return distribution.get(i).count();
    }

    private static <T> List<T> pickSubset(List<T> source, Random random, List<CountDistribution> distribution) {
        int count = pickCount(random, distribution);
        List<T> items = new ArrayList<>(source);
        Collections.shuffle(items, random);
        return new ArrayList<>(items.subList(0, count));
    }

    private static List<String> joinAllCombinations(String[]... parts) {
        int size = 1;
        for (String[] part : parts) {
            size *= part.length;
        }
        List<String> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            StringBuilder item = new StringBuilder();
            int step = 1;
            for (String[] part : parts) {
                item.append(' ').append(part[(i / step) % part.length]);
                step *= part.length;
            }
            out.add(item.substring(1));
        }
        return out;
    }
}
