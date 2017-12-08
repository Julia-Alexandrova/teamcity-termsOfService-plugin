package jetbrains.buildServer.termsOfService;

import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.controllers.login.RememberUrl;
import jetbrains.buildServer.controllers.overview.OverviewController;
import jetbrains.buildServer.users.impl.UserEx;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import jetbrains.buildServer.web.util.SessionUser;
import jetbrains.buildServer.web.util.WebUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static jetbrains.buildServer.termsOfService.TermsOfServiceManager.LOGGER;

public class TermsOfServiceController extends BaseController {

    protected static final String ACCEPT_TERMS_OF_SERVICE_JSP = "acceptTermsOfService.jsp";
    protected static final String TERMS_OF_SERVICE_JSP = "termsOfService.jsp";

    @NotNull
    private final String myResourcesPath;
    @NotNull
    private final TermsOfServiceManager myManager;

    public TermsOfServiceController(@NotNull WebControllerManager webControllerManager,
                                    @NotNull PluginDescriptor descriptor,
                                    @NotNull TermsOfServiceManager manager) {
        myManager = manager;
        webControllerManager.registerController(TermsOfServiceHandlerInterceptor.getEntryPoint(myManager.getConfig().getPath()), this);
        myResourcesPath = descriptor.getPluginResourcesPath();
    }

    @Nullable
    @Override
    protected ModelAndView doHandle(@NotNull final HttpServletRequest request, @NotNull final HttpServletResponse response) throws Exception {
        UserEx user = (UserEx) SessionUser.getUser(request);
        if (user == null) {
            LOGGER.warn("User is set to null. TermsOfServiceController should not be executed.");
            return null;
        }
        if (!myManager.shouldAccept(user)) {
            LOGGER.warn("Acceptance of this terms of service is not required for this user: " + user);
            return null;
        }
        if (isPost(request)) {
            return doPost(user, request, response);
        } else {
            return doGet(user);
        }
    }


    private ModelAndView doGet(@NotNull UserEx user) {
        ModelAndView view = new ModelAndView(!myManager.isAccepted(user) ?
                myResourcesPath + ACCEPT_TERMS_OF_SERVICE_JSP :
                myResourcesPath + TERMS_OF_SERVICE_JSP);
        view.addObject("agreementText", myManager.getConfig().getAgreementText(user));
        view.addObject("termsOfServiceName", myManager.getConfig().getFullDisplayName());
        return view;
    }


    private ModelAndView doPost(@NotNull UserEx user, @NotNull final HttpServletRequest request,
                                @NotNull final HttpServletResponse response) {
        myManager.accept(user);
        String next = RememberUrl.readAndForget(request);
        if (next == null) {
            next = OverviewController.getOverviewPageUrl(request);
        } else {
            next = WebUtil.getServletContext(request).getContextPath().concat(next);
        }
        try {
            response.sendRedirect(next);
        } catch (IOException e) {
            //
        }
        return null;
    }

}

