package com.raceguard.model;

import java.util.ArrayList;
import java.util.List;

public class MethodInfo {

    public String name;

    public boolean hasScheduledAnnotation;

    public String concurrentTrigger;

    public List<FieldAccess> accesses = new ArrayList<>();

    public List<RiskNote> riskNotes = new ArrayList<>();

}