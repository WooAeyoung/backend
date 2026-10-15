package kr.co.wooaeyoung.domain.standard;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.wooaeyoung.domain.standard.StandardModels.*;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** standards/의 JSON·CSV를 읽어 검증된 불변 모델로 만든다. 무결성 오류가 있으면 예외로 시작을 막는다. */
public final class StandardLoader {
    private static final List<String> RECORD_FIELDS = List.of("profile", "nutrientId", "lifeStage", "min", "max", "unit", "basis",
            "sourceDocument", "sourceTable", "sourceFootnote", "verificationStatus", "verifiedBy", "verifiedAt");

    /** 상대 경로(예: "nias_v3/records.csv")의 UTF-8 내용을 돌려준다. */
    @FunctionalInterface
    public interface Source {
        String read(String path) throws IOException;
    }

    private StandardLoader() {}

    private static ObjectMapper strict(ObjectMapper mapper) {
        return mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public static NutrientCatalog loadCatalog(ObjectMapper mapper, Source source) {
        NutrientCatalog catalog;
        try {
            catalog = strict(mapper).readValue(source.read("nutrients.json"), NutrientCatalog.class);
        } catch (IOException error) {
            throw new StandardDataException("영양소 마스터를 읽을 수 없습니다: " + error.getMessage(), error);
        }
        List<String> errors = StandardValidator.validateCatalog(catalog);
        if (!errors.isEmpty()) throw new StandardDataException("영양소 마스터 오류:\n- " + String.join("\n- ", errors));
        return catalog;
    }

    public static StandardProfile loadProfile(ObjectMapper mapper, Source source, StandardProfileId id, NutrientCatalog catalog) {
        StandardProfile profile;
        List<String> errors = new ArrayList<>();
        try {
            ObjectNode document = (ObjectNode) mapper.readTree(source.read(id.directory() + "/profile.json"));
            profile = strict(mapper).treeToValue(document, StandardProfile.class);
            profile = profile.withRecords(readRecords(source.read(id.directory() + "/records.csv"), errors));
        } catch (IOException error) {
            throw new StandardDataException(id + " 기준 데이터를 읽을 수 없습니다: " + error.getMessage(), error);
        }
        if (errors.isEmpty()) errors.addAll(StandardValidator.validateProfile(profile, id, catalog));
        if (!errors.isEmpty()) throw new StandardDataException(id + " 기준 데이터 오류:\n- " + String.join("\n- ", errors));
        return profile;
    }

    /** CSV 빈 셀은 0이나 ""가 아니라 null로 읽는다. 기준값은 유한한 양수만 허용한다. */
    static List<StandardRecord> readRecords(String csv, List<String> errors) {
        List<List<String>> rows = parseCsv(csv);
        if (rows.isEmpty() || !rows.get(0).equals(RECORD_FIELDS)) {
            errors.add("records.csv 헤더는 " + RECORD_FIELDS + "여야 합니다.");
            return List.of();
        }
        List<StandardRecord> records = new ArrayList<>();
        for (int line = 1; line < rows.size(); line++) {
            List<String> row = rows.get(line);
            String label = "records.csv " + (line + 1) + "행";
            if (row.size() != RECORD_FIELDS.size()) { errors.add(label + ": 열 수 오류"); continue; }
            Map<String, String> cells = new HashMap<>();
            for (int index = 0; index < row.size(); index++) cells.put(RECORD_FIELDS.get(index), row.get(index).isBlank() ? null : row.get(index));
            try {
                records.add(new StandardRecord(StandardProfileId.valueOf(required(cells, "profile")), required(cells, "nutrientId"),
                        required(cells, "lifeStage"), number(cells.get("min")), number(cells.get("max")), required(cells, "unit"),
                        cells.get("basis"), cells.get("sourceDocument"), cells.get("sourceTable"), cells.get("sourceFootnote"),
                        VerificationStatus.valueOf(required(cells, "verificationStatus")), cells.get("verifiedBy"),
                        cells.get("verifiedAt") == null ? null : LocalDate.parse(cells.get("verifiedAt"))));
            } catch (IllegalArgumentException | DateTimeParseException error) {
                errors.add(label + ": " + error.getMessage());
            }
        }
        return records;
    }

    private static String required(Map<String, String> cells, String name) {
        String value = cells.get(name);
        if (value == null) throw new IllegalArgumentException(name + " 값이 필요합니다.");
        return value;
    }

    private static Double number(String value) {
        if (value == null) return null;
        double parsed;
        try {
            parsed = Double.parseDouble(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("숫자가 아닙니다: " + value);
        }
        if (!Double.isFinite(parsed) || parsed <= 0)
            throw new IllegalArgumentException("기준값은 유한한 양수여야 합니다(빈칸은 0이 아니라 비워 둡니다): " + value);
        return parsed;
    }

    /** 따옴표를 지원하는 최소 CSV 파서(RFC 4180). */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        String content = text.startsWith("﻿") ? text.substring(1) : text;
        for (int index = 0; index < content.length(); index++) {
            char ch = content.charAt(index);
            if (quoted) {
                if (ch == '"' && index + 1 < content.length() && content.charAt(index + 1) == '"') { cell.append('"'); index++; }
                else if (ch == '"') quoted = false;
                else cell.append(ch);
            } else if (ch == '"') quoted = true;
            else if (ch == ',') { row.add(cell.toString()); cell.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && index + 1 < content.length() && content.charAt(index + 1) == '\n') index++;
                row.add(cell.toString()); cell.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                row = new ArrayList<>();
            } else cell.append(ch);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }
}
