// package com.sw.ck.job.controller;

// import com.sw.ck.job.service.JobInfoService;
// import com.sw.ck.job.service.QuartzSchedulerService;
// import com.sw.ck.security.holder.LoginUser;
// import com.sw.ck.security.holder.LoginUserHolder;
// import com.sw.ck.security.support.PermissionService;
// import jakarta.servlet.FilterChain;
// import jakarta.servlet.ServletException;
// import jakarta.servlet.http.HttpServletRequest;
// import jakarta.servlet.http.HttpServletResponse;
// import org.junit.jupiter.api.AfterEach;
// import org.junit.jupiter.api.Test;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;
// import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
// import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
// import org.springframework.security.config.annotation.web.builders.HttpSecurity;
// import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
// import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
// import org.springframework.security.config.http.SessionCreationPolicy;
// import org.springframework.security.core.authority.SimpleGrantedAuthority;
// import org.springframework.security.web.SecurityFilterChain;
// import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
// import org.springframework.security.core.context.SecurityContextHolder;
// import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
// import org.springframework.test.context.web.WebAppConfiguration;
// import org.springframework.test.web.servlet.MockMvc;
// import org.springframework.test.web.servlet.setup.MockMvcBuilders;
// import org.springframework.http.MediaType;
// import org.springframework.web.context.WebApplicationContext;
// import org.springframework.web.servlet.config.annotation.EnableWebMvc;
// import org.springframework.web.filter.OncePerRequestFilter;
// import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.beans.factory.annotation.Qualifier;
// import org.springframework.security.web.FilterChainProxy;
// import jakarta.servlet.Filter;

// import java.io.IOException;
// import java.util.List;

// import static org.mockito.Mockito.mock;
// import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
// import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// /** 请求经过真实 Spring Method Security 的 job 鉴权证据。 */
// @SpringJUnitConfig(JobInfoControllerAuthorizationTest.TestConfig.class)
// @WebAppConfiguration
// class JobInfoControllerAuthorizationTest {

//     @Autowired
//     private MockMvc mockMvc;

//     @AfterEach
//     void tearDown() {
//         TestAuthenticationFilter.permissions = List.of();
//         LoginUserHolder.clear();
//         SecurityContextHolder.clearContext();
//     }

//     @Test
//     void authorizedRequestThenRevocationMustBecomeForbidden() throws Exception {
//         TestAuthenticationFilter.permissions = List.of("job:list");
//         mockMvc.perform(post("/job/info/page").header("X-Test-User", "admin")
//                         .contentType(MediaType.APPLICATION_JSON).content("{}"))
//                 .andExpect(status().isOk());

//         TestAuthenticationFilter.permissions = List.of();
//         mockMvc.perform(post("/job/info/page").header("X-Test-User", "admin")
//                         .contentType(MediaType.APPLICATION_JSON).content("{}"))
//                 .andExpect(status().isForbidden());
//     }

//     @Test
//     void unauthenticatedRequestMustBeUnauthorized() throws Exception {
//         mockMvc.perform(post("/job/info/page").contentType(MediaType.APPLICATION_JSON).content("{}"))
//                 .andExpect(status().isUnauthorized());
//     }

//     @Configuration
//     @EnableWebMvc
//     @EnableWebSecurity
//     @EnableMethodSecurity
//     static class TestConfig {
//         @Bean
//         JobInfoController controller() {
//             return new JobInfoController(mock(JobInfoService.class), mock(QuartzSchedulerService.class));
//         }

//         @Bean("ss")
//         PermissionService permissionService() {
//             return new PermissionService();
//         }

//         @Bean
//         TestAuthenticationFilter testAuthenticationFilter() {
//             return new TestAuthenticationFilter();
//         }

//         @Bean
//         Filter springSecurityFilterChain(HttpSecurity http, TestAuthenticationFilter filter) throws Exception {
//             return new FilterChainProxy(http.csrf(AbstractHttpConfigurer::disable)
//                     .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
//                     .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
//                     .exceptionHandling(ex -> ex
//                             .authenticationEntryPoint((request, response, exception) -> response.setStatus(401))
//                             .accessDeniedHandler((request, response, exception) -> response.setStatus(403)))
//                     .addFilterBefore(filter, AnonymousAuthenticationFilter.class)
//                     .build());
//         }

//         @Bean
//         MockMvc mockMvc(WebApplicationContext context,
//                         @Qualifier("springSecurityFilterChain") Filter chain) {
//             return MockMvcBuilders.webAppContextSetup(context).addFilters(chain).build();
//         }
//     }

//     static class TestAuthenticationFilter extends OncePerRequestFilter {
//         private static volatile List<String> permissions = List.of();

//         @Override
//         protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
//                                         FilterChain filterChain) throws ServletException, IOException {
//             if (request.getHeader("X-Test-User") != null) {
//                 LoginUser user = new LoginUser();
//                 user.setUserId(1L);
//                 user.setUsername("admin");
//                 user.setPermissions(permissions);
//                 LoginUserHolder.set(user);
//                 SecurityContextHolder.getContext().setAuthentication(
//                         new UsernamePasswordAuthenticationToken(user, null,
//                                 List.of(new SimpleGrantedAuthority("ROLE_USER"))));
//             }
//             try {
//                 filterChain.doFilter(request, response);
//             } finally {
//                 LoginUserHolder.clear();
//                 SecurityContextHolder.clearContext();
//             }
//         }
//     }
// }
