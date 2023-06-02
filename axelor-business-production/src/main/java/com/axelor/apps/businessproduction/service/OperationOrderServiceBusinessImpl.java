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
package com.axelor.apps.businessproduction.service;

import com.axelor.apps.base.db.DayPlanning;
import com.axelor.apps.base.db.WeeklyPlanning;
import com.axelor.apps.base.service.weeklyplanning.WeeklyPlanningService;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.repo.EmployeeRepository;
import com.axelor.apps.production.db.Machine;
import com.axelor.apps.production.db.MachineTool;
import com.axelor.apps.production.db.ManufOrder;
import com.axelor.apps.production.db.OperationOrder;
import com.axelor.apps.production.db.ProdHumanResource;
import com.axelor.apps.production.db.ProdProcessLine;
import com.axelor.apps.production.db.WorkCenter;
import com.axelor.apps.production.db.repo.OperationOrderRepository;
import com.axelor.apps.production.exceptions.ProductionExceptionMessage;
import com.axelor.apps.production.service.app.AppProductionService;
import com.axelor.apps.production.service.operationorder.OperationOrderServiceImpl;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.repo.TraceBackRepository;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.lang.invoke.MethodHandles;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OperationOrderServiceBusinessImpl extends OperationOrderServiceImpl {

  private final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Inject protected EmployeeRepository employeeRepository;

  @Inject protected WeeklyPlanningService weeklyPlanningService;

  @Override
  @Transactional(rollbackOn = {Exception.class})
  public OperationOrder createOperationOrder(ManufOrder manufOrder, ProdProcessLine prodProcessLine)
      throws AxelorException {
    AppProductionService appProductionService = Beans.get(AppProductionService.class);
    if (!appProductionService.isApp("production")
        || !appProductionService.getAppProduction().getManageBusinessProduction()) {
      return super.createOperationOrder(manufOrder, prodProcessLine);
    }

    if (prodProcessLine.getWorkCenter() == null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(ProductionExceptionMessage.PROD_PROCESS_LINE_MISSING_WORK_CENTER),
          prodProcessLine.getProdProcess() != null
              ? prodProcessLine.getProdProcess().getCode()
              : "null",
          prodProcessLine.getName());
    }

    WorkCenter workCenter = getWorkCenterFromWorkShop(prodProcessLine, manufOrder);
    OperationOrder operationOrder =
        this.createOperationOrder(
            manufOrder,
            prodProcessLine.getPriority(),
            manufOrder.getIsToInvoice(),
            workCenter,
            workCenter.getMachine(),
            prodProcessLine.getMachineTool(),
            prodProcessLine);

    return Beans.get(OperationOrderRepository.class).save(operationOrder);
  }

  @Transactional(rollbackOn = {Exception.class})
  public OperationOrder createOperationOrder(
      ManufOrder manufOrder,
      int priority,
      boolean isToInvoice,
      WorkCenter workCenter,
      Machine machine,
      MachineTool machineTool,
      ProdProcessLine prodProcessLine)
      throws AxelorException {

    logger.debug(
        "Creation of an operation {} for the manufacturing order {}",
        priority,
        manufOrder.getManufOrderSeq());

    String operationName = prodProcessLine.getName();

    OperationOrder operationOrder =
        new OperationOrder(
            priority,
            this.computeName(manufOrder, priority, operationName),
            operationName,
            manufOrder,
            workCenter,
            machine,
            OperationOrderRepository.STATUS_DRAFT,
            prodProcessLine,
            machineTool);

    operationOrder.setIsToInvoice(isToInvoice);

    this._createHumanResourceListWithEmployee(operationOrder, workCenter);

    return Beans.get(OperationOrderRepository.class).save(operationOrder);
  }

  protected void _createHumanResourceListWithEmployee(
      OperationOrder operationOrder, WorkCenter workCenter) {

    if (workCenter != null && workCenter.getProdHumanResourceList() != null) {

      for (ProdHumanResource prodHumanResource : workCenter.getProdHumanResourceList()) {

        operationOrder.addProdHumanResourceListItem(
            this.createProdHumanResource(prodHumanResource, operationOrder));
      }
    }
  }

  protected ProdHumanResource createProdHumanResource(
      ProdHumanResource prodHumanResource, OperationOrder operationOrder) {
    ProdHumanResource newProdHumanResource =
        new ProdHumanResource(prodHumanResource.getProduct(), operationOrder.getPlannedDuration());

    if (operationOrder.getPlannedStartDateT() == null
        || operationOrder.getPlannedEndDateT() == null) {
      return newProdHumanResource;
    }

    newProdHumanResource.setEmployee(getNextAvailableEmployee(operationOrder));
    return newProdHumanResource;
  }

  protected Employee getNextAvailableEmployee(OperationOrder operationOrder) {
    Employee employee = null;
    if (operationOrder.getPlannedStartDateT() == null
        || operationOrder.getPlannedEndDateT() == null) {
      return employee;
    }
    List<Employee> employees =
        employeeRepository
            .all()
            .filter(
                "NOT EXISTS( "
                    + "SELECT phr.id "
                    + "FROM ProdHumanResource phr "
                    + "WHERE phr.employee = self AND phr.operationOrder.statusSelect != 2 "
                    + "AND ((phr.operationOrder.manufOrder.plannedStartDateT >= :operationOrderStartDate AND phr.operationOrder.manufOrder.plannedEndDateT < :operationOrderEndDate) "
                    + "OR (phr.operationOrder.manufOrder.plannedStartDateT < :operationOrderEndDate AND phr.operationOrder.manufOrder.plannedEndDateT > :operationOrderEndDate) "
                    + "OR (phr.operationOrder.manufOrder.plannedStartDateT <= :operationOrderStartDate AND phr.operationOrder.manufOrder.plannedEndDateT > :operationOrderStartDate) "
                    + "OR (phr.operationOrder.manufOrder.plannedStartDateT <= :operationOrderStartDate AND phr.operationOrder.manufOrder.plannedEndDateT > :operationOrderEndDate) "
                    + "OR (phr.operationOrder.manufOrder.plannedStartDateT = :operationOrderStartDate AND phr.operationOrder.manufOrder.plannedEndDateT = :operationOrderEndDate) "
                    + "))")
            .bind("operationOrderStartDate", operationOrder.getPlannedStartDateT())
            .bind("operationOrderEndDate", operationOrder.getPlannedEndDateT())
            .fetch();
    if (CollectionUtils.isEmpty(employees)) {
      return employee;
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
        employee = employeeIt;
        break;
      }
    }
    return employee;
  }

  @Override
  protected ProdHumanResource copyProdHumanResource(ProdHumanResource prodHumanResource) {
    AppProductionService appProductionService = Beans.get(AppProductionService.class);

    if (!appProductionService.isApp("production")
        || !appProductionService.getAppProduction().getManageBusinessProduction()) {
      return super.copyProdHumanResource(prodHumanResource);
    }

    ProdHumanResource prodHumanResourceCopy =
        new ProdHumanResource(prodHumanResource.getProduct(), prodHumanResource.getDuration());
    prodHumanResourceCopy.setEmployee(prodHumanResource.getEmployee());
    return prodHumanResourceCopy;
  }

  @Override
  public void updateOperations(ManufOrder manufOrder) {
    if (CollectionUtils.isEmpty(manufOrder.getOperationOrderList())) {
      return;
    }
    for (OperationOrder operationOrder : manufOrder.getOperationOrderList()) {
      if (CollectionUtils.isEmpty(operationOrder.getProdHumanResourceList())) {
        continue;
      }
      for (ProdHumanResource prodHumanResource : operationOrder.getProdHumanResourceList()) {
        prodHumanResource.setDuration(operationOrder.getPlannedDuration());
        if (prodHumanResource.getEmployee() != null) {
          continue;
        }
        prodHumanResource.setEmployee(getNextAvailableEmployee(operationOrder));
      }
    }
  }
}
