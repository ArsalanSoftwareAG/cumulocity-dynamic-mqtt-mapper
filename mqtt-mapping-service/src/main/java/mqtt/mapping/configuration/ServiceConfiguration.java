package mqtt.mapping.configuration;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

@Data
@ToString ()
@AllArgsConstructor
public class ServiceConfiguration implements Cloneable {
    public ServiceConfiguration () {
        this.logPayload = false;
        this.logSubstitution = false;
        this.extensionEnabled = true;
    }

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean logPayload;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean logSubstitution;

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean extensionEnabled;
}
