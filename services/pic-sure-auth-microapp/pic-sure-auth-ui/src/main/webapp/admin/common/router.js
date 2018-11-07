define(["common/searchParser", "backbone", "common/session", "login/login", 'header/header', 'user/userManagement', 'termsOfService/tos'],
        function(searchParser, Backbone, session, login, header, userManagement, tos){
    var Router = Backbone.Router.extend({
        routes: {
            "userManagement(/)" : "displayUserManagement",
            "login(/)" : "login",
            "logout(/)" : "logout",
            "tos(/)" : "displayTOS",
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
            $('#main-content').empty().append(userMngmt.$el);
//            $('#user-div').append(userMngmt.$el);
        },
        displayTOS : function(){
            var headerView = header.View;
            headerView.render();
            $('#header-content').append(headerView.$el);

            var tos = new this.tos.View({model: new this.tos.Model()});
            tos.render();
            $('#main-content').empty().append(tos.$el);
//            $('#tos-div').append(tos.$el);

        }
    });
    return new Router();
});