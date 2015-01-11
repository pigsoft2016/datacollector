/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.lib.stage.processor.selector;

import com.streamsets.pipeline.api.BatchMaker;
import com.streamsets.pipeline.api.ChooserMode;
import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.GenerateResourceBundle;
import com.streamsets.pipeline.api.LanePredicateMapping;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageDef;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.ValueChooser;
import com.streamsets.pipeline.api.base.RecordProcessor;
import com.streamsets.pipeline.el.ELBasicSupport;
import com.streamsets.pipeline.el.ELEvaluator;
import com.streamsets.pipeline.el.ELRecordSupport;
import com.streamsets.pipeline.lib.util.StageLibError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.jsp.el.ELException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@GenerateResourceBundle
@StageDef(version = "1.0.0", label = "Lane Selector",
    description = "Lane Selector based on record predicates", icon="laneSelector.png")
public class SelectorProcessor extends RecordProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(SelectorProcessor.class);

  @ConfigDef(required = true,
      type = ConfigDef.Type.MODEL,
      label = "Lane/Record-predicate mapping",
      description = "Associates output lanes with predicates records must match in order to go to the lane")
  @LanePredicateMapping
  public List<Map<String, String>> lanePredicates;

  @ConfigDef(required = true,
      type = ConfigDef.Type.MODEL,
      label = "Not Matching Predicate Action",
      description = "Action to take with records not matching any predicate",
      defaultValue = "DROP_RECORD")
  @ValueChooser(type = ChooserMode.PROVIDED, chooserValues = OnNoPredicateMatchChooserValues.class)
  public OnNoPredicateMatch onNoPredicateMatch;

  @ConfigDef(required = true,
      type = ConfigDef.Type.MAP,
      label = "Constants for predicates",
      description = "Defines constant values available to all predicates")
  public Map<String, ?> constants;

  private String[][] predicateLanes;
  private ELEvaluator elEvaluator;
  private ELEvaluator.Variables variables;

  private String[][] parsePredicateLanes(List<Map<String, String>> predicateLanesList) throws StageException {
    String[][] predicateLanes = new String[predicateLanesList.size()][];
    int count = 0;
    for (Map<String, String> predicateLaneMap : predicateLanesList) {
      String outputLane = predicateLaneMap.get("outputLane");
      Object predicate = predicateLaneMap.get("predicate");
      if (!getContext().getOutputLanes().contains(outputLane)) {
        throw new StageException(StageLibError.LIB_0012, outputLane, predicate);
      }
      predicateLanes[count] = new String[2];
      predicateLanes[count][0] = "${" + predicate + "}";
      predicateLanes[count][1] = outputLane;
      LOG.debug("Predicate:'{}' Lane:'{}'", predicate, outputLane);
      count++;
    }
    return predicateLanes;
  }

  @SuppressWarnings("unchecked")
  private ELEvaluator.Variables parseConstants(Map<String,?> constants) throws StageException {
    ELEvaluator.Variables variables = new ELEvaluator.Variables();
    if (constants != null) {
      for (Map.Entry<String, ?> entry : constants.entrySet()) {
        variables.addVariable(entry.getKey(), entry.getValue());
        LOG.debug("Variable: {}='{}'", entry.getKey(), entry.getValue());
      }
    }
    return variables;
  }

  @Override
  protected void init() throws StageException {
    super.init();
    if (lanePredicates == null || lanePredicates.size() == 0) {
      throw new StageException(StageLibError.LIB_0010);
    }
    if (getContext().getOutputLanes().size() != lanePredicates.size()) {
      throw new StageException(StageLibError.LIB_0011, getContext().getOutputLanes(), lanePredicates.size());
    }
    predicateLanes = parsePredicateLanes(lanePredicates);
    variables = parseConstants(constants);
    elEvaluator = new ELEvaluator();
    ELBasicSupport.registerBasicFunctions(elEvaluator);
    ELRecordSupport.registerRecordFunctions(elEvaluator);
    validateELs();
    LOG.debug("All predicates validated");
  }

  private void validateELs() throws StageException {

    Record record = new Record(){
      @Override
      public Header getHeader() {
        return null;
      }

      @Override
      public Field get() {
        return null;
      }

      @Override
      public Field set(Field field) {
        return null;
      }

      @Override
      public Field get(String fieldPath) {
        return null;
      }

      @Override
      public Field delete(String fieldPath) {
        return null;
      }

      @Override
      public boolean has(String fieldPath) {
        return false;
      }

      @Override
      public Set<String> getFieldPaths() {
        return null;
      }

      @Override
      public Field set(String fieldPath, Field newField) {
        return null;
      }

      @Override
      public void add(String fieldPath, Field newField) {
      }
    };

    variables.addVariable("default", false);
    ELRecordSupport.setRecordInContext(variables, record);
    for (String[] predicateLane : predicateLanes) {
      try {
        elEvaluator.eval(variables, predicateLane[0], Boolean.class);
      } catch (ELException ex) {
        throw new StageException(StageLibError.LIB_0013, predicateLane[0], ex.getMessage(), ex);
      }
    }
  }

  @Override
  protected void process(Record record, BatchMaker batchMaker) throws StageException {
    boolean matchedAtLeastOnePredicate = false;
    ELRecordSupport.setRecordInContext(variables, record);
    for (String[] pl : predicateLanes) {
      variables.addVariable("default", !matchedAtLeastOnePredicate);
      try {
        if (elEvaluator.eval(variables, pl[0], Boolean.class)) {
          LOG.trace("Record '{}' satisfies predicate '{}', going to lane '{}'", record.getHeader().getSourceId(),
                    pl[0], pl[1]);
          batchMaker.addRecord(record, pl[1]);
          matchedAtLeastOnePredicate = true;
        } else{
          LOG.trace("Record '{}' does not satisfy predicate '{}', skipping lane '{}'", record.getHeader().getSourceId(),
                    pl[0], pl[1]);
        }
      } catch (ELException ex) {
        getContext().toError(record, StageLibError.LIB_0014, pl[0], ex.getMessage(), ex);
      }
    }
    if (!matchedAtLeastOnePredicate) {
      switch (onNoPredicateMatch) {
        case DROP_RECORD:
          LOG.trace("Record '{}' does not satisfy any predicate, dropping it", record.getHeader().getSourceId());
          break;
        case RECORD_TO_ERROR:
          LOG.trace("Record '{}' does not satisfy any predicate, sending it to error",
                    record.getHeader().getSourceId());
          getContext().toError(record, StageLibError.LIB_0015);
          break;
        case FAIL_PIPELINE:
          LOG.error(StageLibError.LIB_0016.getMessage(), record.getHeader().getSourceId());
          throw new StageException(StageLibError.LIB_0016, record.getHeader().getSourceId());
      }
    }
  }

}
