import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class ExpenseTrackerApp {

    // Simple categories to start. You can add more or let users define custom categories.
    enum Category {
        INCOME, FOOD, TRANSPORT, HOUSING, UTILITIES, ENTERTAINMENT, HEALTH, EDUCATION, SHOPPING, OTHER
    }

    enum Type { INCOME, EXPENSE }

    static class Txn {
        final String id;            // UUID string
        final LocalDate date;       // yyyy-MM-dd
        final Type type;            // INCOME or EXPENSE
        final Category category;    // Category enum
        final BigDecimal amount;    // use BigDecimal for money
        final String note;          // optional

        Txn(String id, LocalDate date, Type type, Category category, BigDecimal amount, String note) {
            this.id = id;
            this.date = date;
            this.type = type;
            this.category = category;
            this.amount = amount;
            this.note = note == null ? "" : note;
        }

        static Txn income(LocalDate date, BigDecimal amount, String note) {
            return new Txn(UUID.randomUUID().toString(), date, Type.INCOME, Category.INCOME, amount, note);
        }

        static Txn expense(LocalDate date, Category cat, BigDecimal amount, String note) {
            return new Txn(UUID.randomUUID().toString(), date, Type.EXPENSE, cat, amount, note);
        }
    }

    static class Budget { // monthly budget per category
        final Category category;
        final BigDecimal monthlyLimit;

        Budget(Category category, BigDecimal monthlyLimit) {
            this.category = category;
            this.monthlyLimit = monthlyLimit;
        }
    }

    static class Tracker {
        private final List<Txn> txns = new ArrayList<>();
        private final Map<Category, Budget> budgets = new EnumMap<>(Category.class);
        private final Path csvPath;
        private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        Tracker(String filePath) {
            this.csvPath = Paths.get(filePath);
            load();
        }

        void addIncome(LocalDate date, BigDecimal amount, String note) {
            txns.add(Txn.income(date, amount, note));
            save();
        }

        void addExpense(LocalDate date, Category category, BigDecimal amount, String note) {
            txns.add(Txn.expense(date, category, amount, note));
            save();
        }

        List<Txn> listAll() {
            return txns.stream()
                    .sorted(Comparator.comparing(t -> t.date))
                    .collect(Collectors.toList());
        }

        List<Txn> listByMonth(YearMonth ym) {
            return txns.stream()
                    .filter(t -> YearMonth.from(t.date).equals(ym))
                    .sorted(Comparator.comparing(t -> t.date))
                    .collect(Collectors.toList());
        }

        List<Txn> listByDateRange(LocalDate start, LocalDate end) {
            return txns.stream()
                    .filter(t -> !t.date.isBefore(start) && !t.date.isAfter(end))
                    .sorted(Comparator.comparing(t -> t.date))
                    .collect(Collectors.toList());
        }

        BigDecimal totalIncome(YearMonth ym) {
            return sum(ym, Type.INCOME, null);
        }

        BigDecimal totalExpense(YearMonth ym) {
            return sum(ym, Type.EXPENSE, null);
        }

        BigDecimal totalExpenseByCategory(YearMonth ym, Category cat) {
            return sum(ym, Type.EXPENSE, cat);
        }

        private BigDecimal sum(YearMonth ym, Type type, Category maybeCat) {
            return txns.stream()
                    .filter(t -> YearMonth.from(t.date).equals(ym))
                    .filter(t -> t.type == type)
                    .filter(t -> maybeCat == null || t.category == maybeCat)
                    .map(t -> t.amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        void setMonthlyBudget(Category cat, BigDecimal limit) {
            budgets.put(cat, new Budget(cat, limit));
        }

        Map<Category, Budget> getBudgets() {
            return budgets;
        }

        List<String> budgetAlerts(YearMonth ym) {
            List<String> alerts = new ArrayList<>();
            for (var entry : budgets.entrySet()) {
                Category cat = entry.getKey();
                Budget b = entry.getValue();
                if (cat == Category.INCOME) continue;
                BigDecimal spent = totalExpenseByCategory(ym, cat);
                int cmp = spent.compareTo(b.monthlyLimit);
                if (cmp > 0) {
                    alerts.add(cat + " is over budget. Spent " + money(spent) + " of " + money(b.monthlyLimit));
                } else {
                    BigDecimal remaining = b.monthlyLimit.subtract(spent);
                    // Notify if at or above 80 percent
                    if (b.monthlyLimit.signum() > 0) {
                        BigDecimal eighty = b.monthlyLimit.multiply(new BigDecimal("0.80"));
                        if (spent.compareTo(eighty) >= 0) {
                            alerts.add(cat + " is at 80 percent or more. Spent " + money(spent) + " of " + money(b.monthlyLimit) + ". Remaining " + money(remaining));
                        }
                    }
                }
            }
            return alerts;
        }

        static String money(BigDecimal x) {
            return "$" + x.setScale(2, BigDecimal.ROUND_HALF_UP);
        }

        private void load() {
            if (!Files.exists(csvPath)) return;
            try (BufferedReader br = Files.newBufferedReader(csvPath)) {
                String line;
                // CSV header: id,date,type,category,amount,note
                // budgets header: #BUDGET,category,limit
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue;
                    if (line.startsWith("#BUDGET")) {
                        // format: #BUDGET,category,limit
                        String[] p = splitCsv(line);
                        Category cat = Category.valueOf(p[1]);
                        BigDecimal lim = new BigDecimal(p[2]);
                        budgets.put(cat, new Budget(cat, lim));
                    } else if (!line.startsWith("id,")) {
                        String[] p = splitCsv(line);
                        String id = p[0];
                        LocalDate date = LocalDate.parse(p[1], FMT);
                        Type type = Type.valueOf(p[2]);
                        Category cat = Category.valueOf(p[3]);
                        BigDecimal amount = new BigDecimal(p[4]);
                        String note = p.length > 5 ? unescapeCsv(p[5]) : "";
                        txns.add(new Txn(id, date, type, cat, amount, note));
                    }
                }
            } catch (IOException e) {
                System.err.println("Failed to load data: " + e.getMessage());
            }
        }

        private void save() {
            try (BufferedWriter bw = Files.newBufferedWriter(csvPath)) {
                bw.write("id,date,type,category,amount,note");
                bw.newLine();
                for (Txn t : txns) {
                    bw.write(String.join(",",
                            t.id,
                            t.date.format(FMT),
                            t.type.name(),
                            t.category.name(),
                            t.amount.toPlainString(),
                            escapeCsv(t.note)));
                    bw.newLine();
                }
                // budgets saved after transactions
                for (var b : budgets.values()) {
                    bw.write(String.join(",",
                            "#BUDGET",
                            b.category.name(),
                            b.monthlyLimit.toPlainString()));
                    bw.newLine();
                }
            } catch (IOException e) {
                System.err.println("Failed to save data: " + e.getMessage());
            }
        }

        // Minimal CSV handling for a single field that may contain commas or quotes
        private static String escapeCsv(String s) {
            if (s == null) return "";
            if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
                s = s.replace("\"", "\"\"");
                return "\"" + s + "\"";
            }
            return s;
        }
        private static String unescapeCsv(String s) {
            s = s.trim();
            if (s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length() - 1).replace("\"\"", "\"");
            }
            return s;
        }
        private static String[] splitCsv(String line) {
            List<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean inQuotes = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (inQuotes) {
                    if (c == '\"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '\"') {
                            cur.append('\"');
                            i++;
                        } else {
                            inQuotes = false;
                        }
                    } else {
                        cur.append(c);
                    }
                } else {
                    if (c == '\"') {
                        inQuotes = true;
                    } else if (c == ',') {
                        out.add(cur.toString());
                        cur.setLength(0);
                    } else {
                        cur.append(c);
                    }
                }
            }
            out.add(cur.toString());
            return out.toArray(new String[0]);
        }
    }

    // CLI
    private static final Scanner SC = new Scanner(System.in);

    public static void main(String[] args) {
        System.out.println("Personal Expense Tracker");
        Tracker tracker = new Tracker("expenses.csv");

        while (true) {
            System.out.println("\nChoose an option:");
            System.out.println("1) Add expense");
            System.out.println("2) Add income");
            System.out.println("3) List all");
            System.out.println("4) List by month");
            System.out.println("5) Summary for a month");
            System.out.println("6) Set monthly budget for a category");
            System.out.println("7) Show budget alerts for a month");
            System.out.println("8) List by date range");
            System.out.println("9) Quit");
            System.out.print("Enter choice: ");
            String choice = SC.nextLine().trim();

            try {
                switch (choice) {
                    case "1": addExpenseFlow(tracker); break;
                    case "2": addIncomeFlow(tracker); break;
                    case "3": listAllFlow(tracker); break;
                    case "4": listByMonthFlow(tracker); break;
                    case "5": summaryFlow(tracker); break;
                    case "6": setBudgetFlow(tracker); break;
                    case "7": alertsFlow(tracker); break;
                    case "8": rangeFlow(tracker); break;
                    case "9": System.out.println("Goodbye."); return;
                    default: System.out.println("Invalid choice.");
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    private static void addExpenseFlow(Tracker tr) {
        LocalDate date = readDate("Date (yyyy-MM-dd): ");
        Category cat = readCategory("Category " + Arrays.toString(Category.values()) + ": ");
        if (cat == Category.INCOME) {
            System.out.println("Pick a non income category for expenses.");
            return;
        }
        BigDecimal amount = readMoney("Amount: ");
        System.out.print("Note (optional): ");
        String note = SC.nextLine();
        tr.addExpense(date, cat, amount, note);
        System.out.println("Expense added.");
    }

    private static void addIncomeFlow(Tracker tr) {
        LocalDate date = readDate("Date (yyyy-MM-dd): ");
        BigDecimal amount = readMoney("Amount: ");
        System.out.print("Note (optional): ");
        String note = SC.nextLine();
        tr.addIncome(date, amount, note);
        System.out.println("Income added.");
    }

    private static void listAllFlow(Tracker tr) {
        List<Txn> list = tr.listAll();
        printTxns(list);
        System.out.println("Total transactions: " + list.size());
    }

    private static void listByMonthFlow(Tracker tr) {
        YearMonth ym = readYearMonth("Month (yyyy-MM): ");
        List<Txn> list = tr.listByMonth(ym);
        printTxns(list);
        System.out.println("Total in " + ym + ": " + list.size());
    }

    private static void rangeFlow(Tracker tr) {
        LocalDate start = readDate("Start date (yyyy-MM-dd): ");
        LocalDate end = readDate("End date   (yyyy-MM-dd): ");
        if (end.isBefore(start)) throw new IllegalArgumentException("End date before start date");
        List<Txn> list = tr.listByDateRange(start, end);
        printTxns(list);
        System.out.println("Total transactions in range: " + list.size());
    }

    private static void summaryFlow(Tracker tr) {
        YearMonth ym = readYearMonth("Month (yyyy-MM): ");
        BigDecimal inc = tr.totalIncome(ym);
        BigDecimal exp = tr.totalExpense(ym);
        BigDecimal net = inc.subtract(exp);
        System.out.println("\nSummary for " + ym);
        System.out.println("Income:  " + Tracker.money(inc));
        System.out.println("Expense: " + Tracker.money(exp));
        System.out.println("Net:     " + Tracker.money(net));

        // category breakdown
        System.out.println("\nCategory breakdown:");
        for (Category c : Category.values()) {
            if (c == Category.INCOME) continue;
            BigDecimal amt = tr.totalExpenseByCategory(ym, c);
            if (amt.signum() > 0) {
                System.out.printf("%-15s %s%n", c.name(), Tracker.money(amt));
            }
        }
    }

    private static void setBudgetFlow(Tracker tr) {
        Category cat = readCategory("Set budget for category " + Arrays.toString(Category.values()) + ": ");
        if (cat == Category.INCOME) {
            System.out.println("No budget for income.");
            return;
        }
        BigDecimal limit = readMoney("Monthly limit: ");
        tr.setMonthlyBudget(cat, limit);
        System.out.println("Budget set for " + cat + " at " + Tracker.money(limit));
    }

    private static void alertsFlow(Tracker tr) {
        YearMonth ym = readYearMonth("Month to check (yyyy-MM): ");
        List<String> alerts = tr.budgetAlerts(ym);
        if (alerts.isEmpty()) {
            System.out.println("No alerts for " + ym + ".");
        } else {
            System.out.println("Alerts for " + ym + ":");
            alerts.forEach(a -> System.out.println("• " + a));
        }
    }

    // Helpers
    private static LocalDate readDate(String prompt) {
        System.out.print(prompt);
        String s = SC.nextLine().trim();
        return LocalDate.parse(s);
    }

    private static YearMonth readYearMonth(String prompt) {
        System.out.print(prompt);
        String s = SC.nextLine().trim();
        return YearMonth.parse(s);
    }

    private static BigDecimal readMoney(String prompt) {
        System.out.print(prompt);
        String s = SC.nextLine().trim();
        return new BigDecimal(s);
    }

    private static Category readCategory(String prompt) {
        System.out.print(prompt);
        String s = SC.nextLine().trim().toUpperCase(Locale.ROOT);
        return Category.valueOf(s);
    }

    private static void printTxns(List<Txn> list) {
        if (list.isEmpty()) {
            System.out.println("No transactions.");
            return;
        }
        System.out.printf("%-36s  %-10s  %-7s  %-13s  %-10s  %s%n",
                "ID", "Date", "Type", "Category", "Amount", "Note");
        for (Txn t : list) {
            System.out.printf("%-36s  %-10s  %-7s  %-13s  %-10s  %s%n",
                    t.id,
                    t.date,
                    t.type,
                    t.category,
                    t.amount.setScale(2, BigDecimal.ROUND_HALF_UP),
                    t.note);
        }
    }
}

