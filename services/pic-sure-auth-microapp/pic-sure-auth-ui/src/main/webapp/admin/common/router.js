define(["common/searchParser", "backbone", "common/session", "login/login", 'header/header', 'user/userManagement', 'role/roleManagement', 'privilege/privilegeManagement'],
        function(searchParser, Backbone, session, login, header, userManagement, roleManagement, privilegeManagement){
    var Router = Backbone.Router.extend({
        routes: {
            "userManagement(/)" : "displayUserManagement",
            "login(/)" : "login",
            "logout(/)" : "logout",
            "roleManagement(/)" : "displayRoleManagement",
            "privilegeManagement(/)" : "displayPrivilegeManagement",

            "*path" : "displayUserManagement"


        },
        initialize: function(){
            var pushState = history.pushState;
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
            if( ! session.isValid()){
                this.login();
                return false;
            }
            if (callback) {
                callback.apply(this, args);
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
            $('#user-div').append(userMngmt.$el);
        },

        displayRoleManagement : function(){
            var headerView = header.View;
            headerView.render();
            $('#header-content').append(headerView.$el);

            var roleMngmt = new roleManagement.View({model: new roleManagement.Model()});
            roleMngmt.render();
            $('#user-div').append(roleMngmt.$el);
        },

        displayPrivilegeManagement : function(){
            var headerView = header.View;
            headerView.render();
            $('#header-content').append(headerView.$el);

            var privMngmt = new privilegeManagement.View({model: new privilegeManagement.Model()});
            privMngmt.render();
            $('#user-div').append(privMngmt.$el);
        }
    });
    return new Router();
});