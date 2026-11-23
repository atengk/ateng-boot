package io.github.atengk.boot.sample.excel.vo;

import io.github.atengk.boot.excel.annotation.ExcelMerge;
import io.github.atengk.boot.excel.annotation.ExcelTheme;
import io.github.atengk.boot.excel.enums.ExcelThemeEnum;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serializable;

/**
 * 部门考勤纵向单元格合并示例视图对象
 * <p>
 * 演示 @ExcelMerge 纵向相邻相同值单元格自动合并能力（部门名称与考勤日期多列合并）。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-08
 */
@ExcelTheme(theme = ExcelThemeEnum.BUSINESS_BLUE)
public class DepartmentAttendanceMergeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 部门名称（相同部门相邻行自动合并）
     */
    @ExcelMerge
    @ExcelProperty(value = "部门名称", index = 0)
    private String department;

    /**
     * 考勤日期（相同日期相邻行自动合并）
     */
    @ExcelMerge
    @ExcelProperty(value = "考勤日期", index = 1)
    private String attendanceDate;

    /**
     * 员工姓名
     */
    @ExcelProperty(value = "员工姓名", index = 2)
    private String employeeName;

    /**
     * 打卡时间
     */
    @ExcelProperty(value = "打卡时间", index = 3)
    private String checkInTime;

    /**
     * 考勤状态
     */
    @ExcelProperty(value = "考勤状态", index = 4)
    private String attendanceStatus;

    public DepartmentAttendanceMergeVO() {
    }

    public DepartmentAttendanceMergeVO(String department, String attendanceDate, String employeeName, String checkInTime, String attendanceStatus) {
        this.department = department;
        this.attendanceDate = attendanceDate;
        this.employeeName = employeeName;
        this.checkInTime = checkInTime;
        this.attendanceStatus = attendanceStatus;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String department;
        private String attendanceDate;
        private String employeeName;
        private String checkInTime;
        private String attendanceStatus;

        public Builder department(String department) {
            this.department = department;
            return this;
        }

        public Builder attendanceDate(String attendanceDate) {
            this.attendanceDate = attendanceDate;
            return this;
        }

        public Builder employeeName(String employeeName) {
            this.employeeName = employeeName;
            return this;
        }

        public Builder checkInTime(String checkInTime) {
            this.checkInTime = checkInTime;
            return this;
        }

        public Builder attendanceStatus(String attendanceStatus) {
            this.attendanceStatus = attendanceStatus;
            return this;
        }

        public DepartmentAttendanceMergeVO build() {
            return new DepartmentAttendanceMergeVO(department, attendanceDate, employeeName, checkInTime, attendanceStatus);
        }
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getAttendanceDate() {
        return attendanceDate;
    }

    public void setAttendanceDate(String attendanceDate) {
        this.attendanceDate = attendanceDate;
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public void setEmployeeName(String employeeName) {
        this.employeeName = employeeName;
    }

    public String getCheckInTime() {
        return checkInTime;
    }

    public void setCheckInTime(String checkInTime) {
        this.checkInTime = checkInTime;
    }

    public String getAttendanceStatus() {
        return attendanceStatus;
    }

    public void setAttendanceStatus(String attendanceStatus) {
        this.attendanceStatus = attendanceStatus;
    }
}
