/* 
 * Serposcope - SEO rank checker https://serposcope.serphacker.com/
 * 
 * Copyright (c) 2016 SERP Hacker
 * @author Pierre Nogues <support@serphacker.com>
 * @license https://opensource.org/licenses/MIT MIT License
 */
package serposcope.controllers.google;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import ninja.Result;
import ninja.Results;

import com.google.inject.Singleton;
import com.serphacker.serposcope.db.base.BaseDB;
import com.serphacker.serposcope.db.base.RunDB;
import com.serphacker.serposcope.db.google.GoogleDB;
import com.serphacker.serposcope.models.base.Config;
import com.serphacker.serposcope.models.base.Event;
import com.serphacker.serposcope.models.base.Group;
import com.serphacker.serposcope.models.base.Run;
import com.serphacker.serposcope.models.google.GoogleBest;
import com.serphacker.serposcope.models.google.GoogleRank;
import static com.serphacker.serposcope.models.google.GoogleRank.UNRANKED;
import com.serphacker.serposcope.models.google.GoogleSearch;
import com.serphacker.serposcope.models.google.GoogleTarget;
import com.serphacker.serposcope.scraper.google.GoogleDevice;
import static com.serphacker.serposcope.scraper.google.GoogleDevice.MOBILE;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import ninja.Context;
import ninja.Router;
import ninja.params.Param;
import ninja.params.PathParam;
import ninja.utils.ResponseStreams;
import org.apache.commons.lang3.StringEscapeUtils;
import org.slf4j.LoggerFactory;

@Singleton
public class GoogleTargetController extends GoogleController {
    
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(GoogleTargetController.class);

    @Inject
    BaseDB baseDB;

    @Inject
    GoogleDB googleDB;

    @Inject
    Router router;

    @Inject
    ObjectMapper objectMapper;

    private final static DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    public static class TargetVariation {

        public TargetVariation(GoogleSearch search, GoogleRank rank) {
            this.search = search;
            this.rank = rank;
        }

        public final GoogleSearch search;
        public final GoogleRank rank;

    }

    public static class TargetRank {

        public TargetRank(int now, int prev, String url) {
            this.now = now;
            this.prev = prev;
            this.url = url;
        }

        public final int now;
        public final int prev;
        public final String url;

        public String getRank() {
            if (now == UNRANKED) {
                return "-";
            }
            return Integer.toString(now);
        }

        public String getDiff() {
            if (prev == UNRANKED && now != UNRANKED) {
                return "in";
            }
            if (prev != UNRANKED && now == UNRANKED) {
                return "out";
            }
            int diff = prev - now;
            if (diff == 0) {
                return "=";
            }
            if (diff > 0) {
                return "+" + diff;
            }
            return Integer.toString(diff);
        }

        public String getDiffClass() {
            String diff = getDiff();
            switch (diff.charAt(0)) {
                case '+':
                case 'i':
                    return "plus";
                case '-':
                case 'o':
                    return "minus";
                default:
                    return "";
            }
        }

        public String getUrl() {
            return url;
        }

    }

    public Result target(Context context,
        @PathParam("targetId") Integer targetId,
        @Param("startDate") String startDateStr,
        @Param("endDate") String endDateStr
    ) {
        GoogleTarget target = getTarget(context, targetId);
        List<GoogleSearch> searches = context.getAttribute("searches", List.class);
        Group group = context.getAttribute("group", Group.class);
        Config config = baseDB.config.getConfig();

        String display = context.getParameter("display", config.getDisplayGoogleTarget());
        if (!Config.VALID_DISPLAY_GOOGLE_TARGET.contains(display) && !"export".equals(display)) {
            display = Config.DEFAULT_DISPLAY_GOOGLE_TARGET;
        }

        if (target == null) {
            context.getFlashScope().error("error.invalidTarget");
            return Results.redirect(router.getReverseRoute(GoogleGroupController.class, "view", "groupId", group.getId()));
        }

        Run minRun = baseDB.run.findFirst(group.getModule(), RunDB.STATUSES_DONE, null);
        Run maxRun = baseDB.run.findLast(group.getModule(), RunDB.STATUSES_DONE, null);

        if (maxRun == null || minRun == null || searches.isEmpty()) {
            String fallbackDisplay = "export".equals(display) ? "table" : display;
            return Results.ok()
                .template("/serposcope/views/google/GoogleTargetController/" + fallbackDisplay + ".ftl.html")
                .render("startDate", "")
                .render("endDate", "")
                .render("display", fallbackDisplay)
                .render("target", target);
        }

        LocalDate minDay = minRun.getDay();
        LocalDate maxDay = maxRun.getDay();

        LocalDate startDate = null;
        if (startDateStr != null) {
            try {
                startDate = LocalDate.parse(startDateStr);
            } catch (Exception ex) {
            }
        }
        LocalDate endDate = null;
        if (endDateStr != null) {
            try {
                endDate = LocalDate.parse(endDateStr);
            } catch (Exception ex) {
            }
        }

        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            startDate = maxDay.minusDays(30);
            endDate = maxDay;
        }

        Run firstRun = baseDB.run.findFirst(group.getModule(), RunDB.STATUSES_DONE, startDate);
        Run lastRun = baseDB.run.findLast(group.getModule(), RunDB.STATUSES_DONE, endDate);

        List<Run> runs = baseDB.run.listDone(firstRun.getId(), lastRun.getId());

        startDate = firstRun.getDay();
        endDate = lastRun.getDay();

        switch (display) {
            case "table":
                return renderTable(group, target, searches, runs, minDay, maxDay, startDate, endDate);
            case "chart":
                return renderChart(group, target, searches, runs, minDay, maxDay, startDate, endDate);
            case "variation":
                return renderVariation(group, target, searches, lastRun, minDay, maxDay, startDate, endDate);
            case "export":
                return renderExport(group, target, searches, runs, minDay, maxDay, startDate, endDate);
            default:
                throw new IllegalStateException();
        }

    }

    protected Result renderVariation(
        Group group,
        GoogleTarget target,
        List<GoogleSearch> searches,
        Run lastRun,
        LocalDate minDay,
        LocalDate maxDay,
        LocalDate startDate,
        LocalDate endDate
    ) {
        String display = "variation";
        List<TargetVariation> ranksUp = new ArrayList<>();
        List<TargetVariation> ranksDown = new ArrayList<>();
        List<TargetVariation> ranksSame = new ArrayList<>();

        Map<Integer, GoogleSearch> searchesById = searches.stream()
            .collect(Collectors.toMap(GoogleSearch::getId, Function.identity()));

        List<GoogleRank> ranks = googleDB.rank.list(lastRun.getId(), group.getId(), target.getId());
        for (GoogleRank rank : ranks) {

            GoogleSearch search = searchesById.get(rank.googleSearchId);
            if (search == null) {
                continue;
            }

            if (rank.diff > 0) {
                ranksDown.add(new TargetVariation(search, rank));
            } else if (rank.diff < 0) {
                ranksUp.add(new TargetVariation(search, rank));
            } else {
                ranksSame.add(new TargetVariation(search, rank));
            }
        }

        Collections.sort(ranksUp, (TargetVariation o1, TargetVariation o2) -> Integer.compare(o1.rank.diff, o2.rank.diff));
        Collections.sort(ranksDown, (TargetVariation o1, TargetVariation o2) -> -Integer.compare(o1.rank.diff, o2.rank.diff));
        Collections.sort(ranksSame, (TargetVariation o1, TargetVariation o2) -> Integer.compare(o1.rank.rank, o2.rank.rank));

        return Results.ok()
            .template("/serposcope/views/google/GoogleTargetController/" + display + ".ftl.html")
            .render("target", target)
            .render("searches", searches)
            .render("startDate", lastRun.getDay().toString())
            .render("endDate", lastRun.getDay().toString())
            .render("minDate", minDay)
            .render("maxDate", maxDay)
            .render("display", display)
            .render("ranksUp", ranksUp)
            .render("ranksDown", ranksDown)
            .render("ranksSame", ranksSame);
    }
    
    protected Result renderTable(
        Group group,
        GoogleTarget target,
        List<GoogleSearch> searches,
        List<Run> runs,
        LocalDate minDay,
        LocalDate maxDay,
        LocalDate startDate,
        LocalDate endDate
    ) {
        String display = "table";
        
        StringBuilder jsonData = new StringBuilder("[{\"id\": -1, \"best\": null, \"days\": [");
        StringBuilder jsonDays = new StringBuilder("[");
        
        if(!runs.isEmpty()){

            // events
            List<Event> events = baseDB.event.list(group, startDate, endDate);
            for (Run run : runs) {
                Event event = null;

                for (Event candidat : events) {
                    if(run.getDay().equals(candidat.getDay())){
                        event = candidat;
                        break;
                    }
                }

                if(event != null){
                    jsonData
                        .append("{\"title\":\"").append(StringEscapeUtils.escapeJson(event.getTitle()))
                        .append("\",\"description\":\"").append(StringEscapeUtils.escapeJson(event.getDescription()))
                        .append("\"},");
                } else {
                    jsonData.append("null,");
                }
            }
            jsonData.deleteCharAt(jsonData.length()-1);
            jsonData.append("]},");  


            for (GoogleSearch search : searches) {
                GoogleBest best = googleDB.rank.getBest(target.getGroupId(), target.getId(), search.getId());
                
                jsonData.append("{\"id\":").append(search.getId())
                    .append(",\"search\":{")
                    .append("\"id\":").append(search.getId())
                    .append(",\"k\":\"").append(StringEscapeUtils.escapeJson(search.getKeyword()))
                    .append("\",\"t\":\"").append(search.getTld() == null ? "" : StringEscapeUtils.escapeJson(search.getTld()))
                    .append("\",\"d\":\"").append(MOBILE.equals(search.getDevice()) ? 'M' : 'D')
                    .append("\",\"l\":\"").append(search.getLocal() == null ? "" : StringEscapeUtils.escapeJson(search.getLocal()))
                    .append("\",\"dc\":\"").append(search.getDatacenter() == null ? "" : StringEscapeUtils.escapeJson(search.getDatacenter()))
                    .append("\",\"c\":\"").append(search.getCustomParameters() == null ? "" : StringEscapeUtils.escapeJson(search.getCustomParameters()))
                    .append("\"}, \"best\":");
                if(best == null){
                    jsonData.append("null,");
                } else {
                    jsonData
                        .append("{\"rank\":").append(best.getRank())
                        .append(",\"date\":\"").append(best.getRunDay() != null ? best.getRunDay().toLocalDate().toString() : "?")
                        .append("\",\"url\":\"").append(StringEscapeUtils.escapeJson(best.getUrl()))
                        .append("\"},");
                }
                jsonData.append("\"days\": [");

                for (Run run : runs) {
                    GoogleRank fullRank = googleDB.rank.getFull(run.getId(), group.getId(), target.getId(), search.getId());
                    if(fullRank != null && fullRank.rank != GoogleRank.UNRANKED) {
                        jsonData.append("{\"r\":").append(fullRank.rank)
                            .append(",\"p\":").append(fullRank.previousRank)
                            .append(",\"u\":\"").append(StringEscapeUtils.escapeJson(fullRank.url))
                            .append("\"},");
                    } else {
                        jsonData.append("{\"r\":32767,\"p\":null,\"u\":null},");
                    }
                }
                jsonData.deleteCharAt(jsonData.length()-1);
                jsonData.append("]},");
            }
            jsonData.deleteCharAt(jsonData.length()-1);
            jsonData.append("]");

            
            // days
            for (Run run : runs) {
                jsonDays.append("\"").append(run.getDay().toString()).append("\",");
            }
            jsonDays.deleteCharAt(jsonDays.length()-1);
            jsonDays.append("]");
        } else {
            jsonData.append("]}]");
            jsonDays.append("]");
        }

        
//        Map<Integer, GoogleBest> bestRanks = new HashMap<>();
//        for (GoogleSearch search : searches) {
//            bestRanks.put(search.getId(), googleDB.rank.getBest(target.getGroupId(), target.getId(), search.getId()));
//        }

        return Results.ok()
            .template("/serposcope/views/google/GoogleTargetController/" + display + ".ftl.html")
            .render("target", target)
            .render("searches", searches)
            .render("startDate", startDate.toString())
            .render("endDate", endDate.toString())
            .render("minDate", minDay)
            .render("maxDate", maxDay)
            .render("display", display)
            .render("days", jsonDays)
            .render("data", jsonData)
            ;
    }    

    protected Result renderTable1(
        Group group,
        GoogleTarget target,
        List<GoogleSearch> searches,
        List<Run> runs,
        LocalDate minDay,
        LocalDate maxDay,
        LocalDate startDate,
        LocalDate endDate
    ) {
        String display = "table";
        Map<LocalDateTime, Map<Integer, TargetRank>> ranks = new LinkedHashMap<>();
        Map<String, Integer> years = new LinkedHashMap<>();
        Map<String, Integer> months = new LinkedHashMap<>();

        int maxRank = 0;

        for (Run run : runs) {
            ranks.put(run.getStarted(), new HashMap<>());
            years.compute(Integer.toString(run.getDay().getYear()), (String t, Integer u) -> u == null ? 1 : u + 1);
            months.compute(run.getDay().format(YEAR_MONTH), (String t, Integer u) -> u == null ? 1 : u + 1);

            for (GoogleSearch search : searches) {
                Map<Integer, TargetRank> dayRanks = ranks.get(run.getStarted());

                GoogleRank fullRank = googleDB.rank.getFull(run.getId(), group.getId(), target.getId(), search.getId());

                if (fullRank != null && fullRank.rank != GoogleRank.UNRANKED) {
                    if (fullRank.rank > maxRank) {
                        maxRank = fullRank.rank;
                    }
                    dayRanks.put(search.getId(), new TargetRank(fullRank.rank, fullRank.previousRank, fullRank.url));
                } else {
                    dayRanks.put(search.getId(), new TargetRank(UNRANKED, 0, null));
                }
            }
        }

        List<Event> events = baseDB.event.list(group, startDate, endDate);
        Map<Integer, GoogleBest> bestRanks = new HashMap<>();
        for (GoogleSearch search : searches) {
            bestRanks.put(search.getId(), googleDB.rank.getBest(target.getGroupId(), target.getId(), search.getId()));
        }

        return Results.ok()
            .template("/serposcope/views/google/GoogleTargetController/" + display + ".ftl.html")
            .render("target", target)
            .render("searches", searches)
            .render("startDate", startDate.toString())
            .render("endDate", endDate.toString())
            .render("minDate", minDay)
            .render("maxDate", maxDay)
            .render("display", display)
            .render("years", years)
            .render("months", months)
            .render("ranks", ranks)
            .render("bestRanks", bestRanks)
            .render("events", events);
    }

    protected Result renderChart(
        Group group,
        GoogleTarget target,
        List<GoogleSearch> searches,
        List<Run> runs,
        LocalDate minDay,
        LocalDate maxDay,
        LocalDate startDate,
        LocalDate endDate
    ) {
        String display = "chart";
        StringBuilder builder = new StringBuilder("{\"searches\": [");
        for (GoogleSearch search : searches) {
            builder.append("\"").append(StringEscapeUtils.escapeJson(search.getKeyword())).append("\",");
        }
        builder.setCharAt(builder.length() - 1, ']');
        builder.append(",\"ranks\": [");

        int maxRank = 0;
        for (Run run : runs) {
            builder.append("\n\t[").append(run.getStarted().toEpochSecond(ZoneOffset.UTC) * 1000l).append(",");
            // calendar
            builder.append("null,");

            for (GoogleSearch search : searches) {
                GoogleRank fullRank = googleDB.rank.getFull(run.getId(), group.getId(), target.getId(), search.getId());
                if (fullRank != null && fullRank.rank != GoogleRank.UNRANKED && fullRank.rank > maxRank) {
                    maxRank = fullRank.rank;
                }
                builder.append(fullRank == null || fullRank.rank == GoogleRank.UNRANKED ? "null" : fullRank.rank).append(',');
            }

            builder.setCharAt(builder.length() - 1, ']');
            builder.append(",");
        }
        builder.setCharAt(builder.length() - 1, ']');
        builder.append(",\n\"maxRank\": ").append(maxRank).append("}");

        List<Event> events = baseDB.event.list(group, startDate, endDate);
        String jsonEvents = null;
        try {
            jsonEvents = objectMapper.writeValueAsString(events);
        } catch (JsonProcessingException ex) {
            jsonEvents = "[]";
        }

        Map<Integer, GoogleBest> bestRanks = new HashMap<>();
        for (GoogleSearch search : searches) {
            bestRanks.put(search.getId(), googleDB.rank.getBest(target.getGroupId(), target.getId(), search.getId()));
        }

        return Results.ok()
            .template("/serposcope/views/google/GoogleTargetController/" + display + ".ftl.html")
            .render("target", target)
            .render("searches", searches)
            .render("startDate", startDate.toString())
            .render("endDate", endDate.toString())
            .render("minDate", minDay)
            .render("maxDate", maxDay)
            .render("display", display)
            .render("ranksJson", builder.toString())
            .render("eventsJson", jsonEvents);
    }

    protected Result renderExport(
        Group group,
        GoogleTarget target,
        List<GoogleSearch> searches,
        List<Run> runs,
        LocalDate minDay,
        LocalDate maxDay,
        LocalDate startDate,
        LocalDate endDate
    ) {

        
        return Results.ok()
            .text()
            .addHeader("Content-Disposition", "attachment; filename=\"export.csv\"")
            .render((Context context, Result result) -> {
                ResponseStreams stream = context.finalizeHeaders(result);
                try (PrintWriter writer = new PrintWriter(stream.getOutputStream())) {
                    writer.println("date,rank,url,target,keyword,device,tld,datacenter,local,custom");
                    for (Run run : runs) {
                        String day = run.getDay().toString();
                        for (GoogleSearch search : searches) {
                            GoogleRank rank = googleDB.rank.getFull(run.getId(), group.getId(), target.getId(), search.getId());
                            writer.append(day).append(",");
                            if(rank != null){
                                writer.append(Integer.toString(rank.rank)).append(",");
                                writer.append(rank.url).append(",");
                            } else {
                                writer.append(",").append(",");
                            }
                            writer.append(StringEscapeUtils.escapeCsv(target.getName())).append(",");
                            writer.append(StringEscapeUtils.escapeCsv(search.getKeyword())).append(",");
                            writer.append(search.getDevice() == GoogleDevice.DESKTOP ? "D" : "M").append(",");
                            writer.append(
                                search.getTld() != null ? 
                                StringEscapeUtils.escapeCsv(search.getTld()) : 
                                ""
                            ).append(",");
                            writer.append(
                                search.getDatacenter() != null ?
                                StringEscapeUtils.escapeCsv(search.getDatacenter()) : 
                                ""
                            ).append(",");
                            writer.append(
                                search.getLocal() != null ?
                                StringEscapeUtils.escapeCsv(search.getLocal()) : 
                                ""
                            ).append(",");
                            writer.append(
                                search.getCustomParameters() != null ?
                                StringEscapeUtils.escapeCsv(search.getCustomParameters()) :
                                ""
                            );
                            writer.append("\n");
                        }
                    }

                } catch (IOException ex) {
                    LOG.warn("error while exporting csv");
                }
        });

    }

}
