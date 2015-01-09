package eu.spaziodati.azkaban

import groovy.grape.Grape
import org.apache.ivy.util.Message
import org.apache.ivy.util.url.CredentialsStore

import java.util.regex.Pattern


class GroovyResolversConfig {

    static final def CFG_PREFIX = "groovy.resolver"

    CredentialsStore original

    static GroovyResolversConfig fromMap(Map cfg) {

        Message.info "[GroovyResolversConfig] Temporary replacing credentials store..."

        GroovyResolversConfig grc = new GroovyResolversConfig();
        grc.original = CredentialsStore.INSTANCE
        CredentialsStore.setNewINSTANCE()

        cfg.each {
            if (it.key.startsWith(CFG_PREFIX)) {
                def split = it.key.split('\\.')
                if (split.length < 3) return
                def name = split[2]

                URI uri = URI.create(it.value)

                def userinfo = uri.userInfo?.split(":")
                def username = userinfo?.length > 0 ? userinfo[0] : ""
                def password = userinfo?.length > 1 ? userinfo[1] : ""

                def root = uri.toString()
                if (uri.userInfo)
                    root.replaceFirst(Pattern.quote(uri.userInfo + "@"), "")

                Message.info "[GroovyResolversConfig] Adding temporary Grape resolver setting: host=${uri.getHost()} username=$username"
                Grape.addResolver(name: name, root: root)
                CredentialsStore.INSTANCE.addCredentials("*", uri.getHost(), username, password)
            }
        }

        return grc
    }

    def restore() {
        Message.info "[GroovyResolversConfig] Original credential store restored"
        CredentialsStore.INSTANCE = original;
    }


    static def configure(Map cfg, Closure block) {

        GroovyResolversConfig grc = fromMap(cfg)
        try {
            block.call()
        } finally {
            grc.restore()
        }

    }



}
