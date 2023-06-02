/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2023 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.businessproduction.web;

import com.axelor.apps.base.db.DayPlanning;
import com.axelor.apps.base.db.WeeklyPlanning;
import com.axelor.apps.base.service.weeklyplanning.WeeklyPlanningService;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.repo.EmployeeRepository;
import com.axelor.apps.production.db.OperationOrder;
import com.axelor.apps.production.db.ProdHumanResource;
import com.axelor.exception.service.TraceBackService;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.apache.commons.collections.CollectionUtils;

@Singleton
public class ProdHumanResourceBusinessController {

  public void setEmployeeDomain(ActionRequest request, ActionResponse response) {
    try {
      ProdHumanResource prodHumanResource = request.getContext().asType(ProdHumanResource.class);
      OperationOrder operationOrder = prodHumanResource.getOperationOrder();
      if (operationOrder == null
          || operationOrder.getPlannedStartDateT() == null
          || operationOrder.getPlannedEndDateT() == null) {
        return;
      }
      List<Long> ids = getEmployees(prodHumanResource);
      response.setAttr("employee", "domain", "self.id IN (" + Joiner.on(',').join(ids) + ")");
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  protected List<Long> getEmployees(ProdHumanResource prodHumanResource) {
    List<Long> ids = Lists.newArrayList(0L);
    OperationOrder operationOrder = prodHumanResource.getOperationOrder();
    List<Employee> employees =
        Beans.get(EmployeeRepository.class)
            .all()
            .filter(
                "NOT EXISTS( "
                    + "SELECT phr.id "
                    + "FROM ProdHumanResource phr "
                    + "WHERE phr.employee = self "
                    + "AND ((phr.operationOrder.plannedStartDateT >= :operationOrderStartDate AND phr.operationOrder.plannedEndDateT <= :operationOrderEndDate) "
                    + "OR (phr.operationOrder.plannedStartDateT <= :operationOrderEndDate AND phr.operationOrder.plannedEndDateT >= :operationOrderEndDate) "
                    + "OR (phr.operationOrder.plannedStartDateT <= :operationOrderStartDate AND phr.operationOrder.plannedEndDateT >= :operationOrderStartDate) "
                    + "OR (phr.operationOrder.plannedStartDateT <= :operationOrderStartDate AND phr.operationOrder.plannedEndDateT >= :operationOrderEndDate) "
                    + "))")
            .bind("operationOrderStartDate", operationOrder.getPlannedStartDateT())
            .bind("operationOrderEndDate", operationOrder.getPlannedEndDateT())
            .fetch();
    if (CollectionUtils.isEmpty(employees)) {
      return ids;
    }
    for (Employee employeeIt : employees) {
      boolean found = false;
      WeeklyPlanning weeklyPlanning = employeeIt.getWeeklyPlanning();
      if (weeklyPlanning == null) {
        continue;
      }
      LocalDate startDate = operationOrder.getPlannedStartDateT().toLocalDate();
      LocalDate endDate = operationOrder.getPlannedEndDateT().toLocalDate();
      LocalTime startDateTime = operationOrder.getPlannedStartDateT().toLocalTime();
      LocalTime endDateTime = operationOrder.getPlannedEndDateT().toLocalTime();
      WeeklyPlanningService weeklyPlanningService = Beans.get(WeeklyPlanningService.class);
      if (startDate.compareTo(endDate) == 0) {
        DayPlanning dayPlanning = weeklyPlanningService.findDayPlanning(weeklyPlanning, startDate);
        LocalTime firstPeriodFrom = dayPlanning.getMorningFrom();
        LocalTime firstPeriodTo = dayPlanning.getMorningTo();
        LocalTime secondPeriodFrom = dayPlanning.getAfternoonFrom();
        LocalTime secondPeriodTo = dayPlanning.getAfternoonTo();
        found =
            (firstPeriodFrom != null
                    && firstPeriodTo != null
                    && firstPeriodFrom.compareTo(startDateTime) <= 0
                    && firstPeriodTo.compareTo(endDateTime) >= 0)
                || (secondPeriodFrom != null
                    && secondPeriodTo != null
                    && secondPeriodFrom.compareTo(startDateTime) <= 0
                    && secondPeriodTo.compareTo(endDateTime) >= 0);
      }
      if (found) {
        ids.add(employeeIt.getId());
      }
    }
    return ids;
  }
}
