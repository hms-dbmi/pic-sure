define(["common/searchParser", "backbone", "common/session", "login/login", 'header/header', 'user/userManagement', 'role/roleManagement', 'privilege/privilegeManagement', 'connection/connectionManagement', 'termsOfService/tos'],
        function(searchParser, Backbone, session, login, header, userManagement, roleManagement, privilegeManagement, connectionManagement, tos){
    var Router = Backbone.Router.extend({
        routes: {
            "userManagement(/)" : "displayUserManagement",
            "connectionManagement(/)" : "displayConnectionManagement",
            "tos(/)" : "displayTOS",
            "login(/)" : "login",
            "logout(/)" : "logout",
            "roleManagement(/)" : "displayRoleManagement",
            "privilegeManagement(/)" : "displayPrivilegeManagement",
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
        displayUserManagement : function(){
            var headerView = header.View;
            headerView.render();
            $('#header-content').append(headerView.$el);

            var userMngmt = new userManagement.View({model: new userManagement.Model()});
            userMngmt.render();
            $('#main-content').html(userMngmt.$el);
        },

        displayTOS : function() {
            var termsOfService = new this.tos.View({model: new this.tos.Model()});
            termsOfService.render();
            $('#main-content').html(termsOfService.$el);
        },

        displayRoleManagement : function(){
            var headerView = header.View;
            headerView.render();
            $('#header-content').append(headerView.$el);
            var roleMngmt = new roleManagement.View({model: new roleManagement.Model()});
            roleMngmt.render();
            $('#main-content').append(roleMngmt.$el);
        },

        displayPrivilegeManagement : function() {
            var headerView = header.View;
            headerView.render();
            $('#header-content').append(headerView.$el);
            var privMngmt = new privilegeManagement.View({model: new privilegeManagement.Model()});
            privMngmt.render();
            $('#main-content').append(privMngmt.$el);
        },

        displayConnectionManagement : function() {
            var headerView = header.View;
            headerView.render();
            $('#header-content').append(headerView.$el);
            var connectionMngmt = new connectionManagement.View({model: new connectionManagement.Model()});
            connectionMngmt.render();
            $('#main-content').append(connectionMngmt.$el);
        }
    });
    return new Router();
});