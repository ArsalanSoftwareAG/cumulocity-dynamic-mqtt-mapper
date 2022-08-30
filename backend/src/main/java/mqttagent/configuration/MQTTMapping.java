package mqttagent.configuration;

import lombok.AllArgsConstructor;
import lombok.Data;
import javax.validation.constraints.NotNull;


@Data
@AllArgsConstructor
public class MQTTMapping {
    @NotNull
    public long id;
    
    @NotNull
    public String topic;

    @NotNull
    public String targetAPI;

    @NotNull
    public String source;

    @NotNull
    public String target;

    @NotNull
    public boolean active;

    @NotNull
    public boolean tested;

    @NotNull
    public boolean createNoExistingDevice;
    
    @NotNull
    public long qos;

    @NotNull
    public MQTTMappingSubstitution[] substitutions;

    @NotNull
    public long lastUpdate;
  }

/**
 * export interface MQTTMapping {
  id: number,
  topic: string,
  targetAPI: string,
  source: string,
  target: string,
  lastUpdate: number
}
 */