package org.yurlib.worker;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class WorkerJson {

    static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private WorkerJson() {}
}
