define(["common/searchParser", "backbone", "common/session", "login/login", 'header/header', 'user/userManagement',
        'role/roleManagement', 'privilege/privilegeManagement', "application/applicationManagement",
        'connection/connectionManagement', 'termsOfService/tos', "picSure/userFunctions",
        'text!login/not_authorized.hbs', 'handlebars', 'accessRule/accessRuleManagement', 'picSure/settings'],
        function(searchParser, Backbone, session, login, header, userManagement, roleManagement,
                 privilegeManagement, applicationManagement, connectionManagement, tos, userFunctions,
                 notAuthorizedTemplate, HBS, accessRuleManagement, settings){
        var Router = Backbone.Router.extend({
        routes: {
            "psamaui/userManagement(/)" : "displayUserManagement",
            "psamaui/connectionManagement(/)" : "displayConnectionManagement",
            "psamaui/tos(/)" : "displayTOS",
            "psamaui/login(/)" : "login",
            "psamaui/logout(/)" : "logout",
            "psamaui/not_authorized(/)" : "not_authorized",
            "psamaui/roleManagement(/)" : "displayRoleManagement",
            "psamaui/privilegeManagement(/)" : "displayPrivilegeManagement",
            "psamaui/applicationManagement(/)" : "displayApplicationManagement",
            "psamaui/accessRuleManagement(/)" : "displayAccessRuleManagement",
            "*path" : "displayUserManagement"
        },
        initialize: function(){
            var pushState = history.pushState;
            //TODO: Why
            this.tos = tos;
            history.pushState = function(state, title, path) {
            		if(state.trigger){
            			this.router.navigate(path, state);
            		}else{
            			this.router.navigate(path, {trigger: true});
            		}
                return pushState.apply(history, arguments);
            }.bind({router:this});
        },
       
        execute: function(callback, args, name){
            if( ! session.isValid(login.handleNotAuthorizedResponse)){
                this.login();
                return false;
            }
            if (!session.acceptedTOS() && name !== 'displayTOS'){
                history.pushState({}, "", "tos");
            }
            else if (callback) {
                if (name !== 'displayTOS' && !sessionStorage.connections) {
                    session.loadSessionVariables(function (){
                        callback.apply(this, args);
                    });
                }
                else {
                    callback.apply(this, args);
                }
            }
        },
        login : function(){
            login.showLoginPage();
        },
        logout : function(){
            sessionStorage.clear();
            window.location = "/logout";
        },
        not_authorized : function(){
            $('#main-content').html(HBS.compile(notAuthorizedTemplate)({helpLink:settings.helpLink}));
        },
        displayUserManagement : function(){
            var headerView = header.View;
            headerView.render();
            $('#header-content').append(headerView.$el);

            userFunctions.me(this, function(data){
                    var userMngmt = new userManagement.View({model: new userManagement.Model()});
                    userMngmt.render();
                    $('#main-content').html(userMngmt.$el);
            });
        },

        displayTOS : function() {
            var headerView = header.View;
            headerView.render();
            $('#header-content').append(headerView.$el);
            
            var termsOfService = new this.tos.View({model: new this.tos.Model()});
            termsOfService.render();
            $('#main-content').html(termsOfService.$el);
        },

        displayApplicationManagement : function(){
            var headerView = header.View;
            headerView.render();
            $('#header-content').append(headerView.$el);

            userFunctions.me(this, function(data){
                if (_.find(data.privileges, function(element){
                    return (element === 'SUPER_ADMIN')
                })) {
                    var appliMngmt = new applicationManagement.View({model: new applicationManagement.Model()});
                    appliMngmt.render();
                    $('#main-content').append(appliMngmt.$el);
                } else {
                    $('#main-content').html(HBS.compile(notAuthorizedTemplate)({}));
                }
            });
        },

        displayRoleManagement : function(){
            var headerView = header.View;
            headerView.render();
            $('#header-content').append(headerView.$el);

            userFunctions.me(this, function(data){
                if (_.find(data.privileges, function(element){
                    return (element === 'SUPER_ADMIN')
                })) {
                    var roleMngmt = new roleManagement.View({model: new roleManagement.Model()});
                    roleMngmt.render();
                    $('#main-content').append(roleMngmt.$el);
                } else {
                    $('#main-content').html(HBS.compile(notAuthorizedTemplate)({}));
                }
            });
        },

        displayPrivilegeManagement : function() {
            var headerView = header.View;
            headerView.render();
            $('#header-content').append(headerView.$el);

            userFunctions.me(this, function(data){
                if (_.find(data.privileges, function(element){
                    return (element === 'SUPER_ADMIN')
                })) {
                    var privMngmt = new privilegeManagement.View({model: new privilegeManagement.Model()});
                    privMngmt.render();
                    $('#main-content').append(privMngmt.$el);
                } else {
                    $('#main-content').html(HBS.compile(notAuthorizedTemplate)({}));
                }
            });
        },
            
        displayAccessRuleManagement : function() {
            var headerView = header.View;
            headerView.render();
            $('#header-content').append(headerView.$el);

            userFunctions.me(this, function(data){
                if (_.find(data.accessRules, function(element){
                    return (element === 'ROLE_SUPER_ADMIN')
                })) {
                    var accRuleMngmt = new accessRuleManagement.View({model: new accessRuleManagement.Model()});
                    accRuleMngmt.render();
                    $('#main-content').append(accRuleMngmt.$el);
                } else {
                    $('#main-content').html(HBS.compile(notAuthorizedTemplate)({}));
                }
            });
        },

        displayConnectionManagement : function() {
            var headerView = header.View;
            headerView.render();
            $('#header-content').append(headerView.$el);

            userFunctions.me(this, function(data){
                if (_.find(data.privileges, function(element){
                    return (element === 'SUPER_ADMIN')
                })) {
                    var connectionMngmt = new connectionManagement.View({model: new connectionManagement.Model()});
                    connectionMngmt.render();
                    $('#main-content').append(connectionMngmt.$el);
                } else {
                    $('#main-content').html(HBS.compile(notAuthorizedTemplate)({}));
                }
            });
        }
    });
    return new Router();
});
