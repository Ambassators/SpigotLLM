package dev.foreground.spigotllm.provider;

import dev.foreground.spigotllm.model.Identity;
import dev.foreground.spigotllm.session.SessionRecord;

public interface ProviderAdapter {
    PromptResult prompt(Identity identity, SessionRecord session, String prompt,
                        ProgressListener progress) throws ProviderException;

    String accountStatus(Identity identity) throws ProviderException;

    void startLink(Identity identity, ProgressListener progress) throws ProviderException;

    boolean submitLinkCode(Identity identity, String code) throws ProviderException;

    void disconnect(Identity identity) throws ProviderException;

    boolean cancel(Identity identity);

    void shutdown();
}
