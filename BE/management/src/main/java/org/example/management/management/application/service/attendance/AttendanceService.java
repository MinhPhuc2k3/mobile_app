package org.example.management.management.application.service.attendance;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.example.management.management.application.model.attendance.AggregateLogRequest;
import org.example.management.management.application.model.attendance.AttendanceResponse;
import org.example.management.management.application.model.attendance.LogRequest;
import org.example.management.management.application.model.attendance.LogResponse;
import org.example.management.management.application.service.images.ImageService;
import org.example.management.management.domain.attendace.Attendance;
import org.example.management.management.domain.attendace.Log;
import org.example.management.management.domain.task.Image;
import org.example.management.management.infastructure.exception.ConstrainViolationException;
import org.example.management.management.infastructure.persistance.AttendanceRepository;
import org.example.management.management.infastructure.persistance.ImageRepository;
import org.example.management.management.infastructure.persistance.JpaUserRepositoryInterface;
import org.example.management.management.infastructure.persistance.LogRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceService {

    private final ImageService imageService;

    private final JpaUserRepositoryInterface userRepository;
    private final LogRepository logRepository;
    private final ImageRepository imageRepository;
    private final AttendanceRepository attendanceRepository;

    private final LogMapper logMapper;

    private static final LocalTime eightAM = LocalTime.of(8, 0);
    private static final LocalTime fiveAM = LocalTime.of(17, 0);

    @Transactional
    public int createLog(LogRequest request) throws IOException {
        if (request.getType() == Log.Type.in
                && request.getLogImage() == null
                && request.getImageId() == null
        ) {
            throw new ConstrainViolationException(
                    "log",
                    "Yêu cầu ảnh checkIn khi Clock In"
            );
        }

        Image image = getImageForCreate(request);

        var userId = request.getUserId();
        this.userRepository.findById(userId)
                .orElseThrow(() ->
                        new ConstrainViolationException(
                                "user",
                                "Không tìm thấy user với id là " + userId
                        ));

        var log = new Log(
                request.getCheckIn(),
                request.getType(),
                image == null ? null : image.getId(),
                request.getNote(),
                request.getLatitude(),
                request.getLongitude(),
                userId
        );

        var logSaved = logRepository.save(log);

        return logSaved.getId();
    }

    private Image getImageForCreate(LogRequest request) throws IOException {
        Integer imageId = request.getImageId();
        MultipartFile file = request.getLogImage();

        Image image = null;
        if (imageId != null) {
            image = this.imageRepository.findById(imageId)
                    .orElseThrow(() ->
                            new ConstrainViolationException(
                                    "image",
                                    "Không tìm thấy Image với Id là " + imageId
                            ));
        }

        if (image == null && file != null) {
            image = imageService.uploadImageWithFile(file);
        }

        return image;
    }

    public LogResponse getLogById(int logId) {
        var log = logRepository.findById(logId)
                .orElseThrow(() ->
                        new ConstrainViolationException(
                                "log",
                                "Không tìm thấy Log với Id là " + logId
                        ));

        Optional<Image> possiblyImage = getImage(log.getLogImageId());

        var user = this.userRepository.findById(log.getUserId())
                .orElseThrow(() ->
                        new ConstrainViolationException(
                                "user",
                                "Không tìm thấy User với Id là " + log.getUserId()
                        ));

        return this.logMapper.toResponse(log, possiblyImage.orElse(null), user);
    }

    private Optional<Image> getImage(Integer logImageId) {
        if (logImageId == null) return Optional.empty();
        return this.imageRepository
                .findById(logImageId);
    }

    @Transactional
    public int aggregateFromLogs(AggregateLogRequest request) {
        LocalDate currentDate = request.getDate();
        if (currentDate == null) currentDate = LocalDate.now();

        int userId = request.getUserId();
        userRepository.findById(userId)
                .orElseThrow(() ->
                        new ConstrainViolationException(
                                "user",
                                "Không tìm thấy User với Id là " + userId
                        ));

        var allLogs = this.logRepository.findByUserIdAndDate(userId, currentDate);
        if (CollectionUtils.isEmpty(allLogs)) {
            throw new ConstrainViolationException(
                    "calculate_attendance",
                    "Yêu cầu phải ClockIn và ClockOut"
            );
        }

        var calculateModel = this.resolveCalculateModel(allLogs);

        var totalHours = calculateTotalHours(calculateModel);

        var attendance = new Attendance(
                currentDate,
                calculateModel.clockIn,
                calculateModel.clockOut,
                calculateModel.actualClockIn,
                calculateModel.actualClockOut,
                totalHours,
                request.getNote()
        );

        var saved = this.attendanceRepository.save(attendance);

        return saved.getId();
    }

    private LocalDate toDate(Instant instant) {
        ZoneId zoneId = ZoneId.systemDefault();

        return instant.atZone(zoneId).toLocalDate();
    }

    private LocalTime calculateTotalHours(CalculateAttendanceModel calculateModel) {
        Duration totalTime = Duration.between(calculateModel.clockIn, calculateModel.clockOut);

        if (CollectionUtils.isNotEmpty(calculateModel.breakTimes)) {
            for (var breakTime : calculateModel.breakTimes) {
                var breakWork = breakTime.breakWork;
                var backWork = breakTime.backWork;

                var durationBreak = Duration.between(breakWork, backWork);

                totalTime = totalTime.minus(durationBreak);
            }
        }

        return LocalTime.MIDNIGHT.plus(totalTime);
    }

    private CalculateAttendanceModel resolveCalculateModel(List<Log> allLogs) {
        Instant clockIn = null;
        Instant clockOut = null;
        List<BreakTimeModel> breakTimes = new ArrayList<>();

        var sortedLogs = allLogs.stream()
                .sorted(Comparator.comparing(Log::getCheckIn))
                .toList();

        int logSize = sortedLogs.size();

        int left = 0;
        int right = logSize - 1;
        while (left < right) {
            var logFromLeft = sortedLogs.get(left);
            if (clockIn == null && logFromLeft.getType() == Log.Type.in && logFromLeft.getCheckIn() != null) {
                clockIn = logFromLeft.getCheckIn();
                left++;
            } else if (clockIn == null) {
                left++;
            }

            var logFromRight = sortedLogs.get(right);
            if (logFromRight.getType() == Log.Type.out && logFromRight.getCheckIn() != null) {
                clockOut = logFromRight.getCheckIn();
                right--;
            } else if (clockOut == null) {
                right--;
            }
        }

        Instant breakWork = null;
        Instant backWork;
        for (Log log : sortedLogs) {
            if (log.getCheckIn() == null) continue;

            if (log.getType() == Log.Type.break_work && breakWork == null) {
                breakWork = log.getCheckIn();
            }

            if (log.getType() == Log.Type.back_work && breakWork != null) {
                backWork = log.getCheckIn();
                // add to model
                breakTimes.add(new BreakTimeModel(toTime(breakWork), toTime(backWork)));
                breakWork = null;
            }
        }

        if (clockIn == null) {
            throw new ConstrainViolationException(
                    "ClockIn",
                    "Phải ClockIn trước"
            );
        }
        var clockInTime = toTime(clockIn);
        clockInTime = clockInTime.isBefore(eightAM) ? eightAM : clockInTime;

        if (clockOut == null) {
            if (log.isDebugEnabled()) {
                log.debug(
                        """
                                ClockIn Or ClockOut is null with allLogs {}
                                """, allLogs);
            }
            return new CalculateAttendanceModel(
                    clockInTime,
                    null,
                    toTime(clockIn),
                    null,
                    breakTimes
            );
        }

        var clockOutTime = toTime(clockOut);
        clockOutTime = clockOutTime.isAfter(fiveAM) ? fiveAM : clockOutTime;

        return new CalculateAttendanceModel(
                clockInTime,
                clockOutTime,
                toTime(clockIn),
                toTime(clockOut),
                breakTimes
        );
    }

    private LocalTime toTime(Instant instant) {
        ZonedDateTime zonedDateTime = instant.atZone(ZoneId.systemDefault());

        return zonedDateTime.toLocalTime();
    }

    public AttendanceResponse getAttendanceById(int attendanceId) {
        var attendance = this.attendanceRepository.findById(attendanceId)
                .orElseThrow(() ->
                        new ConstrainViolationException(
                                "attendance",
                                "Attendance không thấy bởi id = " + attendanceId
                        ));
        return this.logMapper.toResponse(attendance);
    }

    record CalculateAttendanceModel(
            LocalTime clockIn,
            LocalTime clockOut,
            LocalTime actualClockIn,
            LocalTime actualClockOut,
            List<BreakTimeModel> breakTimes
    ) {
    }

    record BreakTimeModel(
            LocalTime breakWork,
            LocalTime backWork
    ) {
    }
}
